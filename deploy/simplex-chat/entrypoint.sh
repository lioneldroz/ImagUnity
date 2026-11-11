#!/bin/sh
# Démarre simplex-chat avec l'API WebSocket sur le port 5225, en utilisant
# uniquement les relais SMP et XFTP de l'environnement de test.
# Les empreintes des relais sont générées à leur premier démarrage :
# on les lit dans leurs volumes de configuration, montés en lecture seule.
set -eu

wait_for() {
    i=0
    while [ ! -s "$1" ]; do
        i=$((i + 1))
        if [ "$i" -gt 60 ]; then
            echo "Fichier introuvable après 60 s : $1" >&2
            exit 1
        fi
        sleep 1
    done
}

wait_for /smp-config/fingerprint
wait_for /xftp-config/fingerprint

SMP_FP=$(tr -d '\r\n' < /smp-config/fingerprint)
XFTP_FP=$(tr -d '\r\n' < /xftp-config/fingerprint)

mkdir -p /data/files

exec simplex-chat \
    -d /data/chat \
    --create-bot-display-name "${BOX_NAME:?BOX_NAME doit être défini}" \
    --create-bot-allow-files \
    --chat-server-port 5225 \
    --server "smp://${SMP_FP}@smp-server" \
    --xftp-server "xftp://${XFTP_FP}@xftp-server" \
    --files-folder /data/files \
    --yes-migrate
