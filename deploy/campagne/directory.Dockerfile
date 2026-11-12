# Service d'annuaire, jar construit par « mvnw package » et copié par prepare.sh.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY imagunity-directory.jar /app/imagunity-directory.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/imagunity-directory.jar"]
