# Dockerfile
# Builds a container image for the Spring Boot JAR.
# Used by docker-compose.yml for all 5 Java services (naming server + 3 nodes).
#
# Why a Dockerfile instead of just using the image directly?
#   We pre-copy the JAR into the image so Docker doesn't need a volume mount.
#   The result is a self-contained image: deploy the image, done.
#
# Base image: Eclipse Temurin 25 JRE (matches our Java 25 loom build locally).
# JRE (not JDK) because we only need to RUN the JAR, not compile anything.

FROM eclipse-temurin:25-jre

# Install curl so the naming server can call the Docker socket REST API
# to start/stop node containers on demand (used by NodeLaunchController).
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Create a clean working directory inside the container
WORKDIR /app

# Copy the built JAR from the Maven target/ folder into the image.
# This runs at BUILD time (docker compose build), not at container start.
# The JAR must already exist (run: .\mvnw.cmd clean package -DskipTests first).
COPY projectDS-0.0.1-SNAPSHOT.jar app.jar

# Document which ports the container may use.
# EXPOSE is informational only — actual port binding is in docker-compose.yml.
# 8080 = naming server HTTP
# 8081-8083 = node HTTP
# 9001-9003 = node TCP (file replication)
EXPOSE 8080 8081 8082 8083 9001 9002 9003

# Default entrypoint: run the JAR.
# Spring Boot profile and port are passed as CMD arguments in docker-compose.yml,
# so this image works for both the naming server and all ring nodes.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
