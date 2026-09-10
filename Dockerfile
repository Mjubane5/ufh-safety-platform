# Build stage
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY backend/pom.xml .
COPY backend/src ./src

# The frontend ships inside the jar rather than as a second deployment.
# Spring Boot serves anything in classpath:/static/ automatically, so once the
# pages are here the same container answers both https://host/login.html and
# https://host/api/incidents. One origin means no CORS, one service means one
# thing to pay for, and one push updates both halves at once.
COPY frontend ./src/main/resources/static

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
