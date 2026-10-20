# One Dockerfile for every service. Build it with the module name:
#   docker build -f docker/Dockerfile --build-arg MODULE=ledger-service -t ledger-service .
# (docker compose does this for you: docker compose --profile apps up -d --build)
#
# Two stages: Maven builds the jar, and only the jar plus a JRE ends up in the final image
# (no compiler, no Maven, no source code).

# ---- Stage 1: build ----
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
# Every module's pom must be present because the parent pom lists them all; only this module's sources are copied.
COPY pom.xml .
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY trading-service/pom.xml trading-service/pom.xml
COPY ${MODULE}/src ${MODULE}/src
# The cache mount keeps downloaded dependencies between builds, so only the first build is slow.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl ${MODULE} -DskipTests package

# ---- Stage 2: run ----
FROM eclipse-temurin:21-jre
ARG MODULE
# curl is what the container health check uses (see docker-compose.yml). The base image normally has it; install it only if not.
RUN command -v curl > /dev/null 2>&1 || (apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*)
# Never run as root inside a container: a bug in the app should not hand out root.
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/${MODULE}/target/*.jar /app/app.jar
USER app
# Size the heap from the container's memory limit instead of the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
