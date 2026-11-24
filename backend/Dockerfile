# syntax=docker/dockerfile:1.7
# CryptoPilot backend image (T-004). Build context: backend/.
#   docker build -t cryptopilot-backend backend
# Tests are not run here: CI runs them before the image is built (T-009).

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml lombok.config ./
# Dependencies first, so a code change does not download them again.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline -DskipTests
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests \
    && cp target/cryptopilot-backend-*.jar /src/app.jar

FROM eclipse-temurin:21.0.8_9-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=build --chown=app:app /src/app.jar app.jar
USER app
EXPOSE 8080
# The heap follows the container's memory limit; JAVA_OPTS adds to it (e.g. -XX:MaxRAMPercentage=70).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" TZ=UTC
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
