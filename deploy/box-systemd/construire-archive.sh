#!/bin/sh
# Prépare l'archive d'installation d'une box (éditeur) : lanceur, version de la box signée, scripts et services.
#   1. construire : (cd backend && ./mvnw -DskipTests package)
#   2. signer :     java -jar backend/release/target/imagunity-release.jar signer --cles editeur.json \
#                     --artefact backend/app/target/imagunity-box.jar --version <v> --sequence <n>
#   3. archiver :   sh deploy/box-systemd/construire-archive.sh <v>
set -eu
VERSION=${1:?version, par exemple 0.14.0}
ICI=$(cd "$(dirname "$0")" && pwd)
RACINE=$(cd "$ICI/../.." && pwd)
BOX="$RACINE/backend/app/target/imagunity-box.jar"
for f in "$BOX" "$BOX.manifeste.json" "$RACINE/backend/release/target/imagunity-release.jar"; do
    [ -f "$f" ] || { echo "Absent : $f (construire, puis signer la version)" >&2; exit 1; }
done
grep -q "\"version\\\\\":\\\\\"$VERSION\\\\\"" "$BOX.manifeste.json" || {
    echo "Le manifeste signé ne porte pas la version $VERSION" >&2; exit 1; }
NOM="imagunity-box-$VERSION"
TRAVAIL=$(mktemp -d)
mkdir -p "$TRAVAIL/$NOM"
cp "$BOX" "$BOX.manifeste.json" "$RACINE/backend/release/target/imagunity-release.jar" "$TRAVAIL/$NOM/"
cp -r "$ICI/bin" "$ICI/systemd" "$ICI/polkit" "$ICI/installer.sh" "$ICI/box.env.exemple" "$TRAVAIL/$NOM/"
tar -C "$TRAVAIL" -czf "$RACINE/$NOM.tar.gz" "$NOM"
rm -r "$TRAVAIL"
echo "Archive : $RACINE/$NOM.tar.gz"
