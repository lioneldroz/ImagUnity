#!/bin/sh
# Prépare le contexte de construction de la campagne : jars de la box, du lanceur, de l'annuaire et du relais
# IU-T (déjà construits), manifeste signé de la version et Dockerfiles.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
BUILD="$HERE/build"
mkdir -p "$BUILD"

JAR="$ROOT/backend/app/target/imagunity-box.jar"
if [ ! -f "$JAR" ]; then
    echo "Jar absent : construire d'abord la box (cd backend && ./mvnw -DskipTests package)" >&2
    exit 1
fi
cp "$JAR" "$BUILD/imagunity-box.jar"
# Manifeste signé de la version (mises à jour signées), s'il existe ; sinon la box démarre sans vérification
# tant que la clé de l'éditeur n'est pas configurée.
rm -f "$BUILD/imagunity-box.jar.manifeste.json"
if [ -f "$JAR.manifeste.json" ]; then
    cp "$JAR.manifeste.json" "$BUILD/imagunity-box.jar.manifeste.json"
fi
RELEASE_JAR="$ROOT/backend/release/target/imagunity-release.jar"
if [ ! -f "$RELEASE_JAR" ]; then
    echo "Lanceur absent : construire d'abord la box (cd backend && ./mvnw -DskipTests package)" >&2
    exit 1
fi
cp "$RELEASE_JAR" "$BUILD/imagunity-release.jar"
cp "$ROOT/backend/release/demarrer.sh" "$BUILD/demarrer.sh"
DIRECTORY_JAR="$ROOT/backend/directory-server/target/imagunity-directory.jar"
if [ -f "$DIRECTORY_JAR" ]; then
    cp "$DIRECTORY_JAR" "$BUILD/imagunity-directory.jar"
fi
RELAY_JAR="$ROOT/backend/relay/target/imagunity-relay-exec.jar"
if [ -f "$RELAY_JAR" ]; then
    cp "$RELAY_JAR" "$BUILD/imagunity-relay.jar"
fi

rm -f "$BUILD/simplex-chat" "$BUILD/force-ipv4.so" "$BUILD/entrypoint.sh" "$BUILD/chat.Dockerfile"
cp "$HERE/box.Dockerfile" "$HERE/directory.Dockerfile" "$HERE/relay.Dockerfile" "$BUILD/"
echo "Contexte prêt : $BUILD"
