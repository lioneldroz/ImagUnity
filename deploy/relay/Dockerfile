# Relais IU-T (IU-FILE). Contexte de build : ../backend
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN sh ./mvnw -B -ntp -DskipTests -pl relay -am package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/relay/target/imagunity-relay-exec.jar /app/imagunity-relay.jar
VOLUME /data
# HTTP : en production, derrière un proxy TLS (HTTPS sur le port 443).
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "/app/imagunity-relay.jar"]
