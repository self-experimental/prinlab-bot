FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /app/data && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /build/target/app.jar /app/app.jar
COPY --chown=10001:10001 config /app/config
COPY --chown=10001:10001 deploy/stepped-block.stl /app/smoke.stl
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8082
EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=5s --start-period=180s --retries=6 \
    CMD curl --fail --silent http://127.0.0.1:8082/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/app.jar"]
