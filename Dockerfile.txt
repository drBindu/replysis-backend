# Step 1: Build the application using a modern Maven image
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Step 2: Run the application using the official Eclipse Temurin JRE
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# Make sure the jar name matches your pom.xml (usually backend-0.0.1-SNAPSHOT.jar)
COPY --from=build /app/target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
