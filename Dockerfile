# syntax=docker/dockerfile:1

# --- build stage (multi-module: app depends on crdt-core) ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY crdt-core/build.gradle ./crdt-core/build.gradle
COPY app/build.gradle ./app/build.gradle
RUN chmod +x gradlew && ./gradlew --no-daemon :app:dependencies > /dev/null 2>&1 || true
COPY crdt-core ./crdt-core
COPY app ./app
RUN ./gradlew --no-daemon :app:bootJar -x test

# --- runtime stage (non-root) ---
FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system appuser && useradd --system --gid appuser appuser
WORKDIR /app
COPY --from=build /app/app/build/libs/*.jar app.jar
USER appuser
EXPOSE 8103
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
