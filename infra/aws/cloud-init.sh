#!/bin/bash
#
# Runs once, on first boot, as root. Installs Docker and nothing else - the
# application arrives later via release.sh, so this file never needs to change
# when the app does.
#
# Output lands in /var/log/cloud-init-output.log on the instance, which is the
# first place to look if release.sh cannot find docker.

set -eux

export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y ca-certificates curl gnupg

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list

apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

systemctl enable --now docker
usermod -aG docker ubuntu

# 2 GB of swap. The JVM is capped well below RAM by compose.prod.yaml, but the
# margin on a t3.small is thin enough that a transient spike - loading an image,
# a GC pause during startup - can otherwise get something OOM-killed with no
# useful diagnostic left behind.
if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p /opt/nexum
chown ubuntu:ubuntu /opt/nexum

# A marker release.sh polls for. Without it, releasing into a half-built
# instance fails on a missing docker binary and looks like a network problem.
touch /opt/nexum/.bootstrapped
