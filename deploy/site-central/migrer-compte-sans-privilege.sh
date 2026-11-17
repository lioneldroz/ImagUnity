#!/bin/sh
# Passage du relais et de l'annuaire à un compte sans privilège (uid et gid 10001), à faire une fois, lors de la mise
# à jour qui l'apporte : les images d'avant tournaient en root, et ce qu'elles ont créé appartient à root.
#   sh migrer-compte-sans-privilege.sh      (en root, dans deploy/site-central, après « docker compose build »)
# - volume du relais (morceaux en transit) : rendu au compte 10001 ;
# - clé de fraîcheur déléguée et sa délégation (config/fraicheur/*.json, créées en 600 par l'outil du responsable) :
#   rendues au compte 10001, toujours en 600.
# Sans danger s'il est relancé. Ensuite : « docker compose up -d ».
set -eu
ICI=$(cd "$(dirname "$0")" && pwd)
cd "$ICI"
COMPTE=10001:10001
# Conteneur ponctuel du relais, lancé en root pour la seule commande chown, sans démarrer ses dépendances.
docker compose run --rm --no-deps --user 0:0 --entrypoint chown relay -R "$COMPTE" /data
echo "Volume du relais rendu au compte $COMPTE"
for FICHIER in config/fraicheur/fraicheur.json config/fraicheur/delegation.json; do
    if [ -f "$FICHIER" ]; then
        chown "$COMPTE" "$FICHIER"
        chmod 600 "$FICHIER"
        echo "Rendu au compte $COMPTE : $FICHIER"
    fi
done
echo "Terminé : docker compose up -d"
