FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir -p /app/data && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /build/target/printlab-bot-0.1.0.jar /app/app.jar
COPY --chown=10001:10001 config /app/config
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/app.jar"]
