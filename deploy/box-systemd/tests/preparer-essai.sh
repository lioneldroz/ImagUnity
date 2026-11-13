#!/bin/sh
# Prépare l'essai d'installation sur Rocky Linux 10 (tests/installation-rocky.sh) : clé d'éditeur d'essai, box signée
# avec elle, archive d'installation. Les jars doivent être construits (backend/app, backend/release).
#   sh deploy/box-systemd/tests/preparer-essai.sh <dossier>
# Puis : docker run --rm -v <dossier>:/essai:ro rockylinux/rockylinux:10 sh /essai/installation-rocky.sh
set -eu
ICI=$(cd "$(dirname "$0")" && pwd)
RACINE=$(cd "$ICI/../../.." && pwd)
DOSSIER=${1:?dossier de l\'essai}
mkdir -p "$DOSSIER/box"
DOSSIER=$(cd "$DOSSIER" && pwd)
cp "$RACINE/backend/app/target/imagunity-box.jar" "$RACINE/backend/release/target/imagunity-release.jar" "$DOSSIER/box/"
OUTIL="java -jar $DOSSIER/box/imagunity-release.jar"
# Clé d'essai sans phrase de passe : elle ne sert qu'à cet essai, et n'est jamais celle de l'éditeur.
$OUTIL cle-creer --editeur "Essai CI" --cles "$DOSSIER/editeur.json" --sans-phrase \
    | grep -o '[A-Z2-7]\{4\}\(-[A-Z2-7]\{4\}\)\{5\}' > "$DOSSIER/empreinte.txt"
cp "$DOSSIER/editeur.json.pub.json" "$DOSSIER/editeur.pub.json"
VERSION=$(unzip -p "$DOSSIER/box/imagunity-box.jar" META-INF/MANIFEST.MF | sed -n 's/^Implementation-Version: //p' | tr -d '\r')
$OUTIL signer --cles "$DOSSIER/editeur.json" --artefact "$DOSSIER/box/imagunity-box.jar" --version "$VERSION" --sequence 1
(cd "$DOSSIER" && sh "$RACINE/deploy/box-systemd/construire-archive.sh" "$DOSSIER/box")
mv "$RACINE/imagunity-box-$VERSION.tar.gz" "$DOSSIER/"
cp "$ICI/installation-rocky.sh" "$DOSSIER/"
echo "Essai prêt : $DOSSIER (empreinte de la clé d'essai : $(cat "$DOSSIER/empreinte.txt"))"
