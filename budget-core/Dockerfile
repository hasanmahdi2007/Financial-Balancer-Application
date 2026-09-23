# budget-core, built and run in the Compose network.
#
# It publishes no port in docker-compose.yml, deliberately: this service trusts the user-id header
# the gateway injects, so anything that could reach it directly could forge that header and read
# another person's bank data. EXPOSE below documents the port for the gateway inside the network; it
# publishes nothing to the host.

FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

# The pom alone first, so the dependency download is cached while the source churns. The network on
# the machine this was written for drops constantly, which makes that cache the difference between a
# ten-second rebuild and a failed one.
COPY pom.xml .
RUN mvn --batch-mode dependency:go-offline

COPY src ./src
# Tests run in CI, against a real Postgres via Testcontainers, which is not available inside a build.
RUN mvn --batch-mode clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
