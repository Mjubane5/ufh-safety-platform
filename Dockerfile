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

# C++ build stage - the algorithms/shortest_path routing engine (real
# shortest-path search over the campus footpath/road graph - see that
# directory's README). Built on Alpine specifically so the resulting binary
# links against musl, matching the eclipse-temurin:*-alpine runtime below -
# a binary built on a glibc image (the default gcc:* images) will not run
# there. `|| true` on the compile is deliberate: if it ever fails - a
# missing package, a future compiler regression, anything - the app must
# still build and deploy with Safe Walk routing falling back to
# GeometricRouteEngine, never a broken deployment over one C++ file. See
# CppRouteEngine's class comment for the fallback this protects.
FROM alpine:3.20 AS cppbuilder
RUN apk add --no-cache g++
WORKDIR /src
COPY algorithms/shortest_path/shortest_path.cpp .
RUN (g++ -O2 -std=c++17 -o shortest_path shortest_path.cpp && echo "shortest_path compiled") \
    || echo "shortest_path did NOT compile - Safe Walk will use the geometric fallback route instead"

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# Only copied in if the build stage above actually produced it - a missing
# binary here is exactly the "unset" case app.routing.cpp-binary already
# handles gracefully (see application.properties), not a startup failure.
COPY --from=cppbuilder /src/shortest_path* ./bin/
COPY algorithms/shortest_path/data/campus_graph.txt ./data/campus_graph.txt

EXPOSE 8080

# Set default environment variables
ENV SPRING_PROFILES_ACTIVE=prod
ENV PORT=8080
ENV ROUTING_CPP_BINARY=/app/bin/shortest_path
ENV ROUTING_GRAPH_FILE=/app/data/campus_graph.txt

ENTRYPOINT ["java", "-jar", "app.jar"]
