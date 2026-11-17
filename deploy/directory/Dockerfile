# Service d'annuaire de l'organisation. Contexte de build : ../backend
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN sh ./mvnw -B -ntp -DskipTests -pl directory-server -am package

FROM eclipse-temurin:25-jre
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
