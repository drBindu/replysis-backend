# Step 1: Build the application using a modern Maven image
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# Step 2: Run the application using the official Eclipse Temurin JRE
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system appuser \
    && useradd --system --gid appuser --create-home appuser
WORKDIR /app
# Make sure the jar name matches your pom.xml (usually backend-0.0.1-SNAPSHOT.jar)
COPY --from=build --chown=appuser:appuser /app/target/backend-0.0.1-SNAPSHOT.jar app.jar
USER appuser
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8082/api/v1/resume/status || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
