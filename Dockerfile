FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /app/target/ledger-service-*.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
