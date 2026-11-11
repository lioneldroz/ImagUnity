# Logiciel ImagUnity, jar construit par « mvnw package » et copié par prepare.sh.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY imagunity-box.jar /app/imagunity-box.jar
VOLUME /data
EXPOSE 8080 11112
ENTRYPOINT ["java", "-jar", "/app/imagunity-box.jar"]
