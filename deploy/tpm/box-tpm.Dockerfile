# Image de la box avec les outils TPM (tpm2-tools), à partir d'une image de box déjà construite.
ARG BASE=imagunity/box:campagne
FROM ${BASE}
RUN apt-get update -q && apt-get install -y --no-install-recommends tpm2-tools \
    && rm -rf /var/lib/apt/lists/*
