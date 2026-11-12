# Logiciel ImagUnity, jar construit par « mvnw package » et copié par prepare.sh, avec le lanceur de mises à jour
# signées et, s'il a été signé, le manifeste de la version (imagunity-box.jar.manifeste.json).
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY imagunity-release.jar demarrer.sh /app/
COPY imagunity-box.jar* /app/
VOLUME /data
EXPOSE 8080 11112
ENTRYPOINT ["/app/demarrer.sh"]
