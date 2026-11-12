# Service d'annuaire de l'organisation. Contexte de build : ../backend
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN sh ./mvnw -B -ntp -DskipTests -pl directory-server -am package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/directory-server/target/imagunity-directory.jar /app/imagunity-directory.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/imagunity-directory.jar"]
