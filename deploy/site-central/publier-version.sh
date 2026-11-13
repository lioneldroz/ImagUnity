#!/bin/sh
# Publie une version signée de la box sur le serveur de mises à jour du site central, après vérification :
#   sh publier-version.sh <dossier> <canal>        canal : pilote ou general
# <dossier> contient imagunity-box.jar, imagunity-box.jar.manifeste.json et imagunity-release.jar (dossier « box »
# d'une livraison signée, ou mises-a-jour/pilote pour promouvoir la version pilote vers « general »).
# Vérifie la signature avec config/editeur.pub.json (empreinte EMPREINTE_EDITEUR du .env) et la présence de la
# version dans le registre (deploy/livraison/registre-versions.json), puis dépose le jar, et seulement ensuite le
# manifeste (latest.json) : une box ne voit jamais un manifeste sans son jar.
set -eu
ICI=$(cd "$(dirname "$0")" && pwd)
SOURCE=$(cd "${1:?dossier de la version}" && pwd)
CANAL=${2:?canal : pilote ou general}
case "$CANAL" in pilote|general) ;; *) echo "Canal inconnu : $CANAL (pilote ou general)" >&2; exit 1 ;; esac
CLE="$ICI/config/editeur.pub.json"
REGISTRE="$ICI/../livraison/registre-versions.json"
for f in "$SOURCE/imagunity-box.jar" "$SOURCE/imagunity-box.jar.manifeste.json" "$SOURCE/imagunity-release.jar" \
        "$CLE" "$REGISTRE" "$ICI/.env"; do
    [ -f "$f" ] || { echo "Absent : $f" >&2; exit 1; }
done
ATTENDUE=$(sed -n 's/^EMPREINTE_EDITEUR=//p' "$ICI/.env")
[ -n "$ATTENDUE" ] || { echo "EMPREINTE_EDITEUR absente du .env" >&2; exit 1; }

# Outil de l'éditeur : Java de la machine, sinon celui de l'image du relais, présente sur le site central (rien à
# télécharger).
IMAGE_JAVA="imagunity/relay:$(sed -n 's/^IMAGUNITY_VERSION=//p' "$ICI/.env" | grep . || echo local)"
lanceur() {
    if command -v java >/dev/null 2>&1; then
        LANG=C.UTF-8 java -jar "$SOURCE/imagunity-release.jar" "$@"
    else
        docker run --rm --network none -e LANG=C.UTF-8 -v "$SOURCE":/version:ro \
            -v "$CLE":/cle/editeur.pub.json:ro --entrypoint java "$IMAGE_JAVA" \
            -jar /version/imagunity-release.jar "$@"
    fi
}
if command -v java >/dev/null 2>&1; then CHEMIN_CLE="$CLE"; MANIFESTE="$SOURCE/imagunity-box.jar.manifeste.json"; JAR="$SOURCE/imagunity-box.jar"
else CHEMIN_CLE=/cle/editeur.pub.json; MANIFESTE=/version/imagunity-box.jar.manifeste.json; JAR=/version/imagunity-box.jar; fi

EMPREINTE=$(lanceur empreinte --cle-publique "$CHEMIN_CLE" | sed 's/.* : //')
[ -n "$EMPREINTE" ] || { echo "Clé de l'éditeur illisible ($CLE), ou Java indisponible (image $IMAGE_JAVA)" >&2; exit 1; }
NORMALISE() { printf '%s' "$1" | tr -d ' -' | tr '[:lower:]' '[:upper:]'; }
if [ "$(NORMALISE "$EMPREINTE")" != "$(NORMALISE "$ATTENDUE")" ]; then
    echo "Clé de l'éditeur inattendue : $EMPREINTE au lieu de $ATTENDUE" >&2
    exit 1
fi
lanceur verifier --cle-publique "$CHEMIN_CLE" --manifeste "$MANIFESTE" --artefact "$JAR"

# La version doit figurer au registre, avec le même numéro et la même empreinte.
VERSION=$(sed -n 's/.*\\"version\\":\\"\([^\\]*\)\\".*/\1/p' "$SOURCE/imagunity-box.jar.manifeste.json")
NUMERO=$(sed -n 's/.*\\"sequence\\":\([0-9]*\).*/\1/p' "$SOURCE/imagunity-box.jar.manifeste.json")
NOM_JAR=$(sed -n 's/.*\\"name\\":\\"\([^\\]*\)\\".*/\1/p' "$SOURCE/imagunity-box.jar.manifeste.json")
EMPREINTE_JAR=$(sha256sum "$SOURCE/imagunity-box.jar" | cut -d' ' -f1)
if ! tr -d ' \n' < "$REGISTRE" | grep -q "\"version\":\"$VERSION\",\"sequence\":$NUMERO,\"sha256\":\"$EMPREINTE_JAR\""; then
    echo "Version $VERSION (n° $NUMERO) absente du registre, ou d'une autre empreinte : signer avec --registre, " \
        "commiter le registre, puis « git pull » ici" >&2
    exit 1
fi

CIBLE="$ICI/mises-a-jour/$CANAL"
mkdir -p "$CIBLE"
if [ "$SOURCE" != "$CIBLE" ]; then
    cp "$SOURCE/imagunity-box.jar" "$CIBLE/.$NOM_JAR.nouveau" && mv "$CIBLE/.$NOM_JAR.nouveau" "$CIBLE/$NOM_JAR"
    cp "$SOURCE/imagunity-box.jar.manifeste.json" "$CIBLE/.manifeste.nouveau"
    cp "$SOURCE/imagunity-release.jar" "$CIBLE/imagunity-release.jar"
    cp "$SOURCE/imagunity-box.jar.manifeste.json" "$CIBLE/imagunity-box.jar.manifeste.json"
    mv "$CIBLE/.manifeste.nouveau" "$CIBLE/latest.json"
fi
echo "Version $VERSION (n° $NUMERO) publiée sur le canal « $CANAL »"
