#!/bin/sh
# Lancé par imagunity-box-choix.service, sous le compte imagunity-maj, avant chaque démarrage de la box : le lanceur
# vérifie la signature de l'éditeur sur chaque version (livrée, déposée, serveur de mises à jour), refuse les retours
# en arrière, puis écrit son choix pour demarrer.sh. La box, sous le compte imagunity, peut lire les versions mais ne
# peut modifier ni elles, ni le lanceur, ni l'état anti-retour (docs/guides/installation-box-rocky10.md). Aucun root.
set -eu
JAVA=${IMAGUNITY_JAVA:-java}
# shellcheck disable=SC2086 # options de la JVM (magasin de certificats d'une autorité interne…)
CHOIX=$("$JAVA" ${IMAGUNITY_JAVA_OPTS:-} -jar /opt/imagunity/imagunity-release.jar choisir)
printf '%s\n' "$CHOIX" > "$RUNTIME_DIRECTORY/choix.nouveau"
mv "$RUNTIME_DIRECTORY/choix.nouveau" "$RUNTIME_DIRECTORY/choix"
