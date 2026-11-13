#!/bin/sh
# Lancé en root par systemd avant chaque démarrage de la box (ExecStartPre=+) : le lanceur vérifie la signature
# de l'éditeur sur chaque version (livrée, déposée, serveur de mises à jour), refuse les retours en arrière, puis
# écrit son choix pour demarrer.sh. La box, sous son compte sans privilège, ne peut modifier ni le lanceur ni les
# versions installées (docs/installation-box-rocky10.md).
set -eu
JAVA=${IMAGUNITY_JAVA:-java}
# shellcheck disable=SC2086 # options de la JVM (magasin de certificats d'une autorité interne…)
CHOIX=$("$JAVA" ${IMAGUNITY_JAVA_OPTS:-} -jar /opt/imagunity/imagunity-release.jar choisir)
printf '%s\n' "$CHOIX" > "$RUNTIME_DIRECTORY/choix"
