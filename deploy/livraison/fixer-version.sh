#!/bin/sh
# Fixe la version unique d'ImagUnity : tous les modules Maven (backend/) et la console (frontend/package.json).
#   sh deploy/livraison/fixer-version.sh 0.15.0            version livrée (avant l'étiquette v0.15.0)
#   sh deploy/livraison/fixer-version.sh 0.16.0-SNAPSHOT   version de développement suivante
# Le jar de la box porte cette version (Implementation-Version) ; l'outil de l'éditeur la reprend en la signant.
set -eu
VERSION=${1:?version, par exemple 0.15.0 ou 0.16.0-SNAPSHOT}
if ! printf '%s\n' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$'; then
    echo "Version invalide : $VERSION (attendu : X.Y.Z ou X.Y.Z-SNAPSHOT)" >&2
    exit 1
fi
RACINE=$(cd "$(dirname "$0")/../.." && pwd)
for POM in "$RACINE"/backend/pom.xml "$RACINE"/backend/*/pom.xml; do
    # La version suit toujours la référence à imagunity-parent (projet parent, ou bloc <parent> d'un module).
    sed -i.avant "/<artifactId>imagunity-parent<\/artifactId>/{n;s#<version>[^<]*</version>#<version>$VERSION</version>#;}" "$POM"
    rm -f "$POM.avant"
done
(cd "$RACINE/frontend" && npm version "$VERSION" --no-git-tag-version --allow-same-version >/dev/null)
TROUVEES=$(grep -A1 "<artifactId>imagunity-parent</artifactId>" "$RACINE"/backend/pom.xml "$RACINE"/backend/*/pom.xml \
    | grep -c "<version>$VERSION</version>")
echo "Version $VERSION : $TROUVEES pom.xml et frontend/package.json"
