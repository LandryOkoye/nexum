# Nexum control plane.
#
# Built from the repo root so the Gradle wrapper and the root build files come
# along:
#   docker build -f nexum-backend/Dockerfile -t nexum-backend .

# ---------------------------------------------------------------- build ----

FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Build files first, sources second. Dependency resolution is the slow part of
# this build (minutes on a cold cache), and it only depends on these files - so
# editing Java code re-runs compilation but not the download.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY nexum-backend/build.gradle nexum-backend/build.gradle

# Fails soft: a dependency resolution error here is not worth failing the image
# for, because the real build below will report it properly. This layer exists
# only to populate the cache.
RUN chmod +x gradlew \
    && ./gradlew :nexum-backend:dependencies --no-daemon --quiet || true

COPY nexum-backend/src nexum-backend/src
# The UI: plain static files copied into the jar by the copyFrontend task, so
# there is no node toolchain in this image and no second deployable.
COPY frontend frontend

RUN ./gradlew :nexum-backend:bootJar --no-daemon \
    && cp nexum-backend/build/libs/*.jar /workspace/nexum.jar

# -------------------------------------------------------------- runtime ----

FROM eclipse-temurin:25-jre

# Runs unprivileged. The container reaches a database and two HTTPS APIs and
# needs nothing else; root would buy an attacker a lot for no benefit to us.
RUN groupadd --system nexum && useradd --system --gid nexum --create-home nexum

WORKDIR /app
COPY --from=build --chown=nexum:nexum /workspace/nexum.jar app.jar

USER nexum
EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the JVM otherwise sizes the heap from the HOST's
# memory and ignores the container limit, then gets OOM-killed by the kernel
# with no Java-level error to explain it. 70% leaves room for metaspace, thread
# stacks, and the driver's direct buffers.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

# ExitOnOutOfMemoryError plus a restart policy is deliberate. A JVM that has
# exhausted its heap does not reliably recover, and a control plane that is up
# but unable to serve is worse than one that restarts: the leases of any runs it
# was hosting simply lapse, and the reaper handles them like any other failure.

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
