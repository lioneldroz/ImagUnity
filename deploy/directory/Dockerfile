# Service d'annuaire de l'organisation. Contexte de build : ../backend
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN sh ./mvnw -B -ntp -DskipTests -pl directory-server -am package

# Java d'exécution : celui d'Ubuntu 24.04, corrigé pour CVE-2026-114938 (« java4shell ») depuis 25.0.5.1, quand
# l'image eclipse-temurin:25-jre ne l'est pas encore. La construction échoue si le Java installé est plus ancien.
FROM ubuntu:24.04
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-25-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && v=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java\.runtime\.version = \([0-9.]*\).*/\1/p') \
    && [ "$(printf '%s\n' 25.0.5.1 "$v" | sort -V | head -n 1)" = 25.0.5.1 ] \
    || { echo "Java $v vulnérable (CVE-2026-114938) : 25.0.5.1 ou plus récent est requis" >&2; exit 1; }
# Compte sans privilège (uid et gid 10001) : l'annuaire est joignable d'Internet. Il lit sa configuration en lecture
# seule (/config) ; la clé de fraîcheur déléguée doit appartenir à ce compte (guide d'installation du site central).
RUN groupadd --system --gid 10001 imagunity \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin \
        imagunity
WORKDIR /app
COPY --from=build /src/directory-server/target/imagunity-directory.jar /app/imagunity-directory.jar
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/imagunity-directory.jar"]
