#!/bin/sh
# Lancé par systemd sous le compte imagunity : démarre la version choisie et vérifiée par choisir.sh.
set -eu
CHOIX=$(cat "$RUNTIME_DIRECTORY/choix")
JAR=$(printf '%s\n' "$CHOIX" | cut -f1)
VERSION=$(printf '%s\n' "$CHOIX" | cut -f2)
NUMERO=$(printf '%s\n' "$CHOIX" | cut -f3)
# Phrase de passe des clés de la box : transmise par systemd (LoadCredential), lisible de ce seul service.
if [ -n "${CREDENTIALS_DIRECTORY:-}" ] && [ -f "$CREDENTIALS_DIRECTORY/phrase-box" ]; then
    export IMAGUNITY_DIRECTORY_IDENTITY_PASSPHRASE_FILE="$CREDENTIALS_DIRECTORY/phrase-box"
fi
JAVA=${IMAGUNITY_JAVA:-java}
# shellcheck disable=SC2086 # options de la JVM, séparées par des espaces
exec "$JAVA" ${IMAGUNITY_JAVA_OPTS:-} -Dimagunity.release.version="$VERSION" \
    -Dimagunity.release.sequence="$NUMERO" -jar "$JAR"
