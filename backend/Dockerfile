# Build stage
FROM gradle:8.11.0-jdk21 AS build

# Set working directory
WORKDIR /home/gradlew

# Copy build files
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
COPY gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY src src/

# Build the application
RUN ./gradlew bootJar --no-daemon --stacktrace

# Runtime stage
FROM eclipse-temurin:21-jre-jammy

# Set working directory
WORKDIR /app

# Copy built jar from build stage
COPY --from=build /home/gradlew/build/libs/*.jar app.jar

# Copy application properties
COPY --from=build /home/gradlew/src/main/resources/application.yml .

# Create app user
RUN groupadd -r spring && useradd -r -g spring spring

# Change ownership of app files
RUN chown -R spring:spring /app

# Switch to non-root user
USER spring

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=prod", "app.jar"]
