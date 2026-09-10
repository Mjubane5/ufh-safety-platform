# Build stage
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY backend/pom.xml .
COPY backend/src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# Set default environment variables
ENV SPRING_PROFILES_ACTIVE=prod
ENV PORT=8080

ENTRYPOINT ["java", "-jar", "app.jar"]
