FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b

# Build the tested JAR on the development machine; do not copy source or local credentials.
WORKDIR /app
COPY --chown=10001:10001 target/creator-agent-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
