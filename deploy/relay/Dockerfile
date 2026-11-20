# Relais IU-T (IU-FILE). Contexte de build : ../backend
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN sh ./mvnw -B -ntp -DskipTests -pl relay -am package

# Java d'exécution : celui d'Ubuntu 24.04, corrigé pour CVE-2026-114938 (« java4shell ») depuis 25.0.5.1, quand
# l'image eclipse-temurin:25-jre ne l'est pas encore. La construction échoue si le Java installé est plus ancien.
FROM ubuntu:24.10
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-25-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && v=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java\.runtime\.version = \([0-9.]*\).*/\1/p') \
    && [ "$(printf '%s\n' 25.0.5.1 "$v" | sort -V | head -n 1)" = 25.0.5.1 ] \
    || { echo "Java $v vulnérable (CVE-2026-114938) : 25.0.5.1 ou plus récent est requis" >&2; exit 1; }
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
