#!/bin/sh
# Prépare l'archive d'installation d'une box (éditeur), à partir d'une version livrée et signée :
#   sh deploy/box-systemd/construire-archive.sh <dossier>
# <dossier> contient imagunity-box.jar, son manifeste signé (imagunity-box.jar.manifeste.json) et
# imagunity-release.jar : en général le dossier « box » de la livraison (docs/guides/livraison.md), après signature.
# Sans argument : les jars construits dans backend/*/target. La version est lue dans le manifeste signé.
set -eu
ICI=$(cd "$(dirname "$0")" && pwd)
RACINE=$(cd "$ICI/../.." && pwd)
if [ $# -ge 1 ]; then
    SOURCE=$(cd "$1" && pwd)
    BOX="$SOURCE/imagunity-box.jar"
    LANCEUR="$SOURCE/imagunity-release.jar"
else
    BOX="$RACINE/backend/app/target/imagunity-box.jar"
    LANCEUR="$RACINE/backend/release/target/imagunity-release.jar"
fi
for f in "$BOX" "$BOX.manifeste.json" "$LANCEUR"; do
    [ -f "$f" ] || { echo "Absent : $f (la version doit être construite, puis signée)" >&2; exit 1; }
done
# Version signée : champ « version » du manifeste (charge utile JSON échappée dans le document signé).
VERSION=$(sed -n 's/.*\\"version\\":\\"\([^\\]*\)\\".*/\1/p' "$BOX.manifeste.json")
[ -n "$VERSION" ] || { echo "Version introuvable dans $BOX.manifeste.json" >&2; exit 1; }
NOM="imagunity-box-$VERSION"
TRAVAIL=$(mktemp -d)
mkdir -p "$TRAVAIL/$NOM"
cp "$BOX" "$BOX.manifeste.json" "$LANCEUR" "$TRAVAIL/$NOM/"
cp -r "$ICI/bin" "$ICI/systemd" "$ICI/polkit" "$ICI/installer.sh" "$ICI/box.env.exemple" "$TRAVAIL/$NOM/"
tar -C "$TRAVAIL" -czf "$RACINE/$NOM.tar.gz" "$NOM"
rm -r "$TRAVAIL"
echo "Archive : $RACINE/$NOM.tar.gz"
