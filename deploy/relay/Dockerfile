# Relais IU-T (IU-FILE). Contexte de build : ../backend
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN sh ./mvnw -B -ntp -DskipTests -pl relay -am package

FROM eclipse-temurin:25-jre
# Compte sans privilège (uid et gid 10001, comme le manifeste k3s du banc d'essai) : le relais est joignable
# d'Internet, une faille de Spring ou de Tomcat ne donne pas root dans le conteneur. Le jar appartient à root, en
# lecture seule pour le service ; /data seul est à lui (un volume neuf hérite de ce propriétaire).
RUN groupadd --system --gid 10001 imagunity \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin \
        imagunity \
    && mkdir -p /data && chown 10001:10001 /data
WORKDIR /app
COPY --from=build /src/relay/target/imagunity-relay-exec.jar /app/imagunity-relay.jar
VOLUME /data
# Morceaux dans le volume par défaut : le dossier de travail (/app) n'est pas inscriptible pour le service.
ENV IMAGUNITY_RELAY_STORAGE_DIR=/data/morceaux
USER 10001:10001
# HTTP : en production, derrière un proxy TLS (HTTPS sur le port 443).
EXPOSE 8443
ENTRYPOINT ["java", "-jar", "/app/imagunity-relay.jar"]
