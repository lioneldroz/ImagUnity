# TPM logiciel (swtpm), pour les essais seulement : en production, la box utilise le TPM de l'hôte.
FROM ubuntu:24.04
RUN apt-get update -q && apt-get install -y --no-install-recommends swtpm swtpm-tools \
    && rm -rf /var/lib/apt/lists/*
VOLUME /var/lib/swtpm
ENTRYPOINT ["swtpm", "socket", "--tpm2", "--tpmstate", "dir=/var/lib/swtpm", \
            "--server", "type=tcp,bindaddr=127.0.0.1,port=2321", "--ctrl", "type=tcp,bindaddr=127.0.0.1,port=2322", \
            "--flags", "not-need-init,startup-clear"]
