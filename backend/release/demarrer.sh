#!/bin/sh
# Démarre la box sur la version signée valide la plus récente (voir docs/mises-a-jour-signees.md) :
# le lanceur vérifie les versions (image, dépôt, serveur de mises à jour) et refuse les retours en arrière.
set -eu
CHOIX=$(java -jar /app/imagunity-release.jar choisir) || exit 1
JAR=$(printf '%s\n' "$CHOIX" | cut -f1)
VERSION=$(printf '%s\n' "$CHOIX" | cut -f2)
NUMERO=$(printf '%s\n' "$CHOIX" | cut -f3)
exec java -Dimagunity.release.version="$VERSION" -Dimagunity.release.sequence="$NUMERO" -jar "$JAR" "$@"
