# Relais IU-T, jar construit par « mvnw package » et copié par prepare.sh.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY imagunity-relay.jar /app/imagunity-relay.jar
VOLUME /data
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "/app/imagunity-relay.jar"]
