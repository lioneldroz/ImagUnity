#!/bin/sh
# Interface du responsable de l'organisation ImagUnity (Linux, macOS). Réglages : variables ci-dessous ou
# d'environnement. Au premier lancement, la page propose de créer l'organisation (onglet « Démarrage »).
set -eu
ICI=$(cd "$(dirname "$0")" && pwd)
ANNUAIRE=${ANNUAIRE:-https://annuaire.exemple.org}
DOSSIER=${DOSSIER:-$ICI/donnees}
JAVA=${JAVA:-java}
JAR=$ICI/imagunity-directory.jar
[ -f "$JAR" ] || JAR=$ICI/../../backend/directory-server/target/imagunity-directory.jar
[ -f "$JAR" ] || { echo "imagunity-directory.jar introuvable à côté de ce lanceur" >&2; exit 1; }
mkdir -p "$DOSSIER"
chmod 700 "$DOSSIER"
exec "$JAVA" -cp "$JAR" -Dloader.main=com.imagunity.directory.DirectoryAdmin \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    interface --cles "$DOSSIER/organisation.json" --codes "$DOSSIER/codes.json" --annuaire "$ANNUAIRE" --ouvrir
