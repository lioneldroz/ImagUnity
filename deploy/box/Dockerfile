# Logiciel ImagUnity de la box. Contexte de build : ../backend
#
# La box démarre par le lanceur de mises à jour signées (imagunity-release.jar) : il vérifie la version livrée
# avec l'image et celles installées depuis, puis démarre la plus récente (docs/mises-a-jour-signees.md).
# Construite ici depuis les sources, la version livrée n'est pas signée : avec la clé de l'éditeur configurée,
# seules les versions signées déposées ou téléchargées démarrent. Pour livrer une image dont la version est
# signée, la construire à partir du jar signé et de son manifeste (comme deploy/campagne/box.Dockerfile).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN sh ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
# Outils TPM : clés de la box protégées par un secret scellé dans le TPM de l'hôte (docs/cles-tpm.md).
RUN apt-get update -q && apt-get install -y --no-install-recommends tpm2-tools \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/release/target/imagunity-release.jar /app/imagunity-release.jar
COPY --from=build /src/release/demarrer.sh /app/demarrer.sh
COPY --from=build /src/app/target/imagunity-box.jar /app/imagunity-box.jar
VOLUME /data
# 8080 : console et API ; 11112 : entrée DICOM (phase 2)
EXPOSE 8080 11112
ENTRYPOINT ["/app/demarrer.sh"]
