# ---------- Stage 1: Build Shadow JAR ----------
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle :server:shadowJar --no-daemon

# ---------- Stage 2: Runtime Container ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/server/build/libs/*-all.jar app.jar

EXPOSE 8080
ENV PORT=8080

CMD ["java", "-jar", "app.jar"]
