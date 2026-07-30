# TASK: ATOM-KAFKA-009
# Build context is the REPO ROOT (see infra/docker-compose.yml) so the shared
# Avro schemas under infra/kafka/schemas are visible to avro-maven-plugin.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY infra/kafka/schemas infra/kafka/schemas
COPY services/audit-service services/audit-service
RUN mvn -f services/audit-service/pom.xml -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/audit-service/target/audit-service-*.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
