#!/bin/sh
# Sauvegarde du site central : bases de l'annuaire et du relais, configuration, et données de Caddy (dont l'autorité
# de certification interne, si TLS_INTERNE=local_certs : la perdre obligerait à redistribuer une racine à toutes les
# box ; avec Let's Encrypt, les certificats sont redemandés au besoin). Les morceaux du relais ne sont pas
# sauvegardés : chiffrés et temporaires, ils sont redéposés par les box au besoin ; ni les mises à jour publiées,
# que l'éditeur peut republier.
#   sh sauvegarder.sh /srv/sauvegardes/imagunity     (par exemple chaque nuit, par un timer systemd)
set -eu
ICI=$(cd "$(dirname "$0")" && pwd)
DEST=${1:?dossier de destination}
DATE=$(date +%Y%m%d-%H%M%S)
# Chemin absolu, résolu avant de changer de dossier : un chemin relatif reste celui de l'appelant.
mkdir -p "$DEST"
CIBLE=$(cd "$DEST" && pwd)
cd "$ICI"
umask 077
# Chaque fichier est écrit sous un nom provisoire, puis renommé une fois complet : une sauvegarde interrompue
# (conteneur arrêté, disque plein) ne laisse pas, sous un nom normal, un fichier tronqué pris pour le plus récent.
trap 'rm -f "$CIBLE"/*-"$DATE".*.partiel' EXIT
docker compose exec -T directory-db pg_dump -U annuaire -Fc annuaire > "$CIBLE/annuaire-$DATE.dump.partiel"
mv "$CIBLE/annuaire-$DATE.dump.partiel" "$CIBLE/annuaire-$DATE.dump"
docker compose exec -T relay-db pg_dump -U relais -Fc relais > "$CIBLE/relais-$DATE.dump.partiel"
mv "$CIBLE/relais-$DATE.dump.partiel" "$CIBLE/relais-$DATE.dump"
tar -czf "$CIBLE/config-$DATE.tar.gz.partiel" .env config Caddyfile
mv "$CIBLE/config-$DATE.tar.gz.partiel" "$CIBLE/config-$DATE.tar.gz"
docker compose exec -T caddy tar -czf - -C /data . > "$CIBLE/caddy-$DATE.tar.gz.partiel"
mv "$CIBLE/caddy-$DATE.tar.gz.partiel" "$CIBLE/caddy-$DATE.tar.gz"
# Garder 30 jours.
find "$CIBLE" -maxdepth 1 -type f -mtime +30 \( -name '*.dump' -o -name 'config-*.tar.gz' -o -name 'caddy-*.tar.gz' \) \
    -delete
echo "Sauvegarde : $CIBLE/*-$DATE.*"
