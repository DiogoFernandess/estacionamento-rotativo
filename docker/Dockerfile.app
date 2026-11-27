# Build do fat JAR e runtime JRE (C6).
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY app/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline
COPY app/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /build/target/estacionamento-rotativo-1.0.0.jar /app/app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
