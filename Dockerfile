FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 --create-home rtgs
WORKDIR /app
COPY --from=build /workspace/target/rtgs-liquidity-engine-0.1.0-SNAPSHOT.jar app.jar
USER rtgs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
