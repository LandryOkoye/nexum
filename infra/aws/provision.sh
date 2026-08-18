#!/usr/bin/env bash
#
# Creates the AWS resources Nexum runs on. Idempotent: every step checks for an
# existing resource first, so re-running after a failure resumes rather than
# duplicating. Run it as often as you like.
#
#   AWS_PROFILE=nexum ./infra/aws/provision.sh
#
# What it does NOT do: create the CockroachDB Cloud cluster, request Bedrock
# model access, or point DNS at the instance. Those are console actions and are
# listed at the end.

set -euo pipefail

REGION="${AWS_REGION:-us-east-1}"
PROFILE="${AWS_PROFILE:-nexum}"
NAME="${NEXUM_STACK:-nexum}"
INSTANCE_TYPE="${NEXUM_INSTANCE_TYPE:-t3.small}"
KEY_PATH="${NEXUM_KEY_PATH:-$HOME/.ssh/${NAME}.pem}"

aws() { command aws --profile "$PROFILE" --region "$REGION" "$@"; }
say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()  { printf '  %-34s %s\n' "$1" "${2:-ok}"; }

# --------------------------------------------------------------- preflight ---

say "Preflight"
command -v aws >/dev/null || { echo "aws cli not found"; exit 1; }
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
ok "account" "$ACCOUNT"
ok "region" "$REGION"

# Fail early and clearly rather than midway through creating things. A partial
# provision is far more annoying to reason about than a refusal.
if ! aws ec2 describe-vpcs --max-items 1 >/dev/null 2>&1; then
    echo
    echo "  This user cannot call EC2. Attach permissions in the console:"
    echo "  IAM -> Users -> ${NAME} -> Add permissions -> AdministratorAccess"
    exit 1
fi
ok "ec2 permissions" "granted"

# ------------------------------------------------------------------- keypair --

say "SSH key pair"
if aws ec2 describe-key-pairs --key-names "$NAME" >/dev/null 2>&1; then
    ok "key pair" "exists ($NAME)"
    [ -f "$KEY_PATH" ] || {
        echo "  ERROR: key '$NAME' exists in AWS but $KEY_PATH is missing."
        echo "  Delete the key pair in the console and re-run, or restore the file."
        exit 1
    }
else
    mkdir -p "$(dirname "$KEY_PATH")"
    aws ec2 create-key-pair --key-name "$NAME" \
        --query KeyMaterial --output text > "$KEY_PATH"
    chmod 600 "$KEY_PATH"
    ok "key pair" "created -> $KEY_PATH"
fi

# ------------------------------------------------------------ security group --

say "Security group"
VPC=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true \
    --query 'Vpcs[0].VpcId' --output text)
ok "default vpc" "$VPC"

SG=$(aws ec2 describe-security-groups \
    --filters Name=group-name,Values="$NAME" Name=vpc-id,Values="$VPC" \
    --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo "None")

if [ "$SG" = "None" ] || [ -z "$SG" ]; then
    SG=$(aws ec2 create-security-group --group-name "$NAME" \
        --description "Nexum control plane" --vpc-id "$VPC" \
        --query GroupId --output text)
    ok "security group" "created $SG"
else
    ok "security group" "exists $SG"
fi

# 80 and 443 open to the world: it is a public demo, and Caddy needs :80
# reachable for the ACME HTTP challenge or no certificate is ever issued.
for PORT in 80 443; do
    aws ec2 authorize-security-group-ingress --group-id "$SG" \
        --protocol tcp --port "$PORT" --cidr 0.0.0.0/0 >/dev/null 2>&1 \
        && ok "ingress :$PORT" "opened" || ok "ingress :$PORT" "already open"
done

# SSH restricted to whoever is running this. A demo box with 22 open to the
# internet is found by scanners within minutes.
MY_IP=$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '\n')
aws ec2 authorize-security-group-ingress --group-id "$SG" \
    --protocol tcp --port 22 --cidr "${MY_IP}/32" >/dev/null 2>&1 \
    && ok "ingress :22" "opened to ${MY_IP}/32" || ok "ingress :22" "already open for ${MY_IP}/32"

# ------------------------------------------------------------- instance role --

# The instance reaches Bedrock and S3 through a role, never through keys on the
# box. This is why compose.prod.yaml deliberately leaves AWS_ACCESS_KEY_ID unset:
# any value there shadows the role and breaks Bedrock in a way that works fine on
# a laptop and fails only in production.
say "Instance role"
ROLE="${NAME}-instance"

if ! aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
    aws iam create-role --role-name "$ROLE" --assume-role-policy-document '{
      "Version": "2012-10-17",
      "Statement": [{
        "Effect": "Allow",
        "Principal": {"Service": "ec2.amazonaws.com"},
        "Action": "sts:AssumeRole"
      }]
    }' >/dev/null
    ok "role" "created $ROLE"
else
    ok "role" "exists $ROLE"
fi

aws iam put-role-policy --role-name "$ROLE" --policy-name nexum-runtime \
    --policy-document '{
      "Version": "2012-10-17",
      "Statement": [
        {"Effect": "Allow",
         "Action": ["bedrock:InvokeModel"],
         "Resource": "arn:aws:bedrock:*::foundation-model/amazon.titan-embed-text-v2:0"},
        {"Effect": "Allow",
         "Action": ["s3:PutObject", "s3:GetObject", "s3:ListBucket"],
         "Resource": ["arn:aws:s3:::'"$NAME"'-*", "arn:aws:s3:::'"$NAME"'-*/*"]}
      ]
    }' >/dev/null
ok "role policy" "bedrock:InvokeModel + s3"

if ! aws iam get-instance-profile --instance-profile-name "$ROLE" >/dev/null 2>&1; then
    aws iam create-instance-profile --instance-profile-name "$ROLE" >/dev/null
    aws iam add-role-to-instance-profile --instance-profile-name "$ROLE" \
        --role-name "$ROLE" >/dev/null
    ok "instance profile" "created"
    # IAM is eventually consistent; RunInstances fails if the profile is not yet
    # visible, and the error names neither IAM nor the reason.
    sleep 12
else
    ok "instance profile" "exists"
fi

# ---------------------------------------------------------------- instance ----

say "EC2 instance"
INSTANCE=$(aws ec2 describe-instances \
    --filters Name=tag:Name,Values="$NAME" \
              Name=instance-state-name,Values=pending,running,stopped \
    --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || echo "None")

if [ "$INSTANCE" = "None" ] || [ -z "$INSTANCE" ]; then
    AMI=$(aws ssm get-parameters \
        --names /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id \
        --query 'Parameters[0].Value' --output text)
    ok "ami (ubuntu 24.04)" "$AMI"

    INSTANCE=$(aws ec2 run-instances \
        --image-id "$AMI" \
        --instance-type "$INSTANCE_TYPE" \
        --key-name "$NAME" \
        --security-group-ids "$SG" \
        --iam-instance-profile "Name=$ROLE" \
        --block-device-mappings 'DeviceName=/dev/sda1,Ebs={VolumeSize=20,VolumeType=gp3}' \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NAME}]" \
        --user-data file://"$(dirname "$0")/cloud-init.sh" \
        --query 'Instances[0].InstanceId' --output text)
    ok "instance" "launched $INSTANCE ($INSTANCE_TYPE)"
else
    ok "instance" "exists $INSTANCE"
    STATE=$(aws ec2 describe-instances --instance-ids "$INSTANCE" \
        --query 'Reservations[0].Instances[0].State.Name' --output text)
    if [ "$STATE" = "stopped" ]; then
        aws ec2 start-instances --instance-ids "$INSTANCE" >/dev/null
        ok "instance" "starting"
    fi
fi

aws ec2 wait instance-running --instance-ids "$INSTANCE"
ok "instance state" "running"

# --------------------------------------------------------------- elastic ip ---

# A static address, because the DNS record and the TLS certificate are both
# pinned to it. A restart handing out a new public IP would invalidate both.
say "Elastic IP"
ALLOC=$(aws ec2 describe-addresses --filters Name=tag:Name,Values="$NAME" \
    --query 'Addresses[0].AllocationId' --output text 2>/dev/null || echo "None")

if [ "$ALLOC" = "None" ] || [ -z "$ALLOC" ]; then
    ALLOC=$(aws ec2 allocate-address --domain vpc \
        --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$NAME}]" \
        --query AllocationId --output text)
    ok "elastic ip" "allocated"
else
    ok "elastic ip" "exists"
fi

aws ec2 associate-address --instance-id "$INSTANCE" --allocation-id "$ALLOC" >/dev/null
PUBLIC_IP=$(aws ec2 describe-addresses --allocation-ids "$ALLOC" \
    --query 'Addresses[0].PublicIp' --output text)
ok "public ip" "$PUBLIC_IP"

# ------------------------------------------------------------------ summary ---

cat <<EOF

$(printf '\033[1mProvisioned.\033[0m')

  instance   $INSTANCE  ($INSTANCE_TYPE)
  public ip  $PUBLIC_IP
  ssh        ssh -i $KEY_PATH ubuntu@$PUBLIC_IP

Before releasing, three things must be true:

  1. DNS  Point your hostname at $PUBLIC_IP and let it propagate.
          Caddy cannot get a certificate until the A record resolves here.
          Check with:  dig +short YOUR_HOST

  2. Bedrock  Amazon Titan Text Embeddings V2 enabled in $REGION.
              Bedrock console -> Model access.

  3. CockroachDB Cloud  A v25.4+ cluster, with the connection details in
                        infra/aws/.env.prod (see .env.prod.example).

Then:  ./infra/aws/release.sh
EOF

echo "$PUBLIC_IP" > "$(dirname "$0")/.public-ip"
echo "$INSTANCE" > "$(dirname "$0")/.instance-id"
