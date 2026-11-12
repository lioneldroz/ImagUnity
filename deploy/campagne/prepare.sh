#!/bin/sh
# Prépare le contexte de construction de la campagne : jar de la box (déjà construit),
# binaire simplex-chat (téléchargé une fois), Dockerfiles et script de démarrage du client.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SIMPLEX_CHAT_VERSION=${SIMPLEX_CHAT_VERSION:-v7.1.2}
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

if [ ! -x "$BUILD/simplex-chat" ]; then
    curl -fsSL -o "$BUILD/simplex-chat" \
        "https://github.com/simplex-chat/simplex-chat/releases/download/${SIMPLEX_CHAT_VERSION}/simplex-chat-ubuntu-24_04-x86_64"
    chmod +x "$BUILD/simplex-chat"
fi

# Hôte sans IPv6 seulement (compose.sans-ipv6.yml) : module qui force l'écoute des relais en IPv4.
if [ ! -f /proc/net/if_inet6 ] && [ ! -f "$BUILD/force-ipv4.so" ]; then
    cc -shared -fPIC -O2 -o "$BUILD/force-ipv4.so" "$HERE/ipv4/force-ipv4.c" -ldl
fi

cp "$ROOT/deploy/simplex-chat/entrypoint.sh" "$HERE/chat.Dockerfile" "$HERE/box.Dockerfile" \
    "$HERE/directory.Dockerfile" "$BUILD/"
echo "Contexte prêt : $BUILD"
