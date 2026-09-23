# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.12_8-jdk-alpine-3.24 AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
# Unit tests and the coverage gate run in CI before the image job.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24 AS runtime
WORKDIR /app
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
COPY --from=build --chown=10001:10001 /workspace/target/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
