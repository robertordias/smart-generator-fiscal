FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline -DskipTests
COPY src/main ./src/main
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S fiscal && adduser -S -G fiscal -u 10001 fiscal
WORKDIR /app
COPY --from=build --chown=fiscal:fiscal /build/target/emissor-fiscal-0.1.0.jar ./app.jar
USER fiscal
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
