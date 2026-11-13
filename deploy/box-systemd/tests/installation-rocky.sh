#!/bin/sh
# Essai d'installation de la box dans un conteneur Rocky Linux 10 (CI, workflow « CI », tâche « Installations »).
#   docker run --rm -v <dossier>:/essai:ro rockylinux/rockylinux:10 sh /essai/installation-rocky.sh
# <dossier> contient l'archive imagunity-box-<v>.tar.gz (version signée par une clé d'essai), la clé publique
# d'essai editeur.pub.json, son empreinte (empreinte.txt) et ce script.
# Vérifie : Java 25 sur Rocky 10, installer.sh (comptes, droits, polkit, réinstallation), le lanceur sous
# imagunity-maj, les droits de la box, et le démarrage de la box sous imagunity.
set -eu
echo "== Paquets"
if [ "${ESSAI_SANS_PAQUETS:-}" = 1 ]; then
    # Essai local sans accès aux dépôts Rocky : Java et pkcheck fournis par le lanceur de l'essai.
    echo "Paquets non installés (ESSAI_SANS_PAQUETS=1)"
elif ! dnf -y -q install tar polkit util-linux findutils curl-minimal >/dev/null; then
    echo "ÉCHEC : paquets de base" >&2; exit 1
elif dnf -y -q install java-25-openjdk-headless >/dev/null 2>&1; then
    echo "Java 25 : paquet de Rocky Linux (java-25-openjdk-headless)"
else
    cat > /etc/yum.repos.d/adoptium.repo <<'REPO'
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/rhel/$releasever/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
REPO
    dnf -y -q install temurin-25-jre >/dev/null
    echo "Java 25 : dépôt Adoptium (temurin-25-jre)"
fi
java -version 2>&1 | head -1

echo "== Installation"
mkdir -p /tmp/archive && tar -xzf /essai/imagunity-box-*.tar.gz -C /tmp/archive
ARCHIVE=$(echo /tmp/archive/imagunity-box-*)
sh "$ARCHIVE/installer.sh"
attendu() {  # attendu <chemin> <droits> <propriétaire:groupe>
    reel="$(stat -c '%a %U:%G' "$1")"
    [ "$reel" = "$2 $3" ] || { echo "ÉCHEC : $1 est « $reel », attendu « $2 $3 »" >&2; exit 1; }
    echo "ok : $1 $2 $3"
}
id imagunity >/dev/null && id imagunity-maj >/dev/null && echo "ok : comptes imagunity et imagunity-maj"
attendu /etc/imagunity 755 root:root
attendu /etc/imagunity/box.env 600 root:root
attendu /etc/imagunity/phrase-box 600 root:root
attendu /opt/imagunity/imagunity-release.jar 644 root:root
attendu /opt/imagunity/bin/choisir.sh 755 root:root
attendu /var/lib/imagunity-maj 755 imagunity-maj:imagunity-maj
attendu /var/lib/imagunity-maj/depot 755 imagunity-maj:imagunity-maj
attendu /etc/polkit-1/rules.d/50-imagunity-maj.rules 644 root:root
for u in imagunity-box.service imagunity-box-choix.service imagunity-box-maj.service imagunity-box-maj.timer \
        imagunity-alerte@.service; do
    [ -f "/etc/systemd/system/$u" ] || { echo "ÉCHEC : $u absent" >&2; exit 1; }
done
echo "ok : unités systemd installées"

echo "== Réinstallation (configuration gardée)"
echo "# réglage du site" >> /etc/imagunity/box.env
AVANT=$(sha256sum /etc/imagunity/box.env /etc/imagunity/phrase-box)
sh "$ARCHIVE/installer.sh" >/dev/null
[ "$AVANT" = "$(sha256sum /etc/imagunity/box.env /etc/imagunity/phrase-box)" ] || { echo "ÉCHEC : configuration écrasée" >&2; exit 1; }
echo "ok : box.env et phrase-box inchangés"

echo "== Lanceur sous imagunity-maj"
install -m 0644 /essai/editeur.pub.json /etc/imagunity/editeur.pub.json
install -d -m 0755 -o imagunity-maj -g imagunity-maj /run/imagunity-choix
runuser -u imagunity-maj -- env LANG=C.UTF-8 IMAGUNITY_RELEASE_KEY=/etc/imagunity/editeur.pub.json \
    IMAGUNITY_RELEASE_CODE="$(cat /essai/empreinte.txt)" IMAGUNITY_RELEASE_DIR=/var/lib/imagunity-maj \
    IMAGUNITY_RELEASE_BUNDLED=/opt/imagunity/imagunity-box.jar RUNTIME_DIRECTORY=/run/imagunity-choix \
    /opt/imagunity/bin/choisir.sh
CHOIX=$(cat /run/imagunity-choix/choix)
echo "choix : $CHOIX"
[ "$(printf '%s' "$CHOIX" | cut -f3)" = 1 ] || { echo "ÉCHEC : version n° 1 attendue" >&2; exit 1; }
attendu /var/lib/imagunity-maj/etat.json 644 imagunity-maj:imagunity-maj

echo "== Droits de la box (compte imagunity)"
for f in /var/lib/imagunity-maj/etat.json /opt/imagunity/imagunity-release.jar /run/imagunity-choix/choix; do
    if runuser -u imagunity -- sh -c "echo x >> '$f'" 2>/dev/null; then echo "ÉCHEC : $f modifiable par la box" >&2; exit 1; fi
done
for f in /etc/imagunity/box.env /etc/imagunity/phrase-box; do
    if runuser -u imagunity -- cat "$f" >/dev/null 2>&1; then echo "ÉCHEC : $f lisible par la box" >&2; exit 1; fi
done
echo "ok : versions, lanceur et choix en lecture seule ; configuration et phrase de passe illisibles"

echo "== Démarrage de la box sous imagunity"
install -d -m 0700 -o imagunity -g imagunity /var/lib/imagunity-box
runuser -u imagunity -- env LANG=C.UTF-8 IMAGUNITY_DATA_DIR=/var/lib/imagunity-box SERVER_ADDRESS=127.0.0.1 \
    IMAGUNITY_DICOM_BIND_ADDRESS=127.0.0.1 IMAGUNITY_CONSOLE_PASSWORD=essai IMAGUNITY_BOX_NAME=box-rocky \
    /opt/imagunity/bin/demarrer.sh > /tmp/box.log 2>&1 &
for _ in $(seq 1 60); do
    curl -sf http://127.0.0.1:8080/actuator/health | grep -q '"UP"' && break
    sleep 2
done
curl -sf http://127.0.0.1:8080/actuator/health | grep -q '"UP"' || { tail -30 /tmp/box.log; echo "ÉCHEC : box non démarrée" >&2; exit 1; }
VERSION=$(curl -s -u operateur:essai http://127.0.0.1:8080/api/v1/box)
echo "box : $VERSION"
# Propriétaire du processus Java de la box, lu dans /proc (sans procps, absent d'une image minimale).
PROPRIETAIRE=""
for dossier in /proc/[0-9]*; do
    if tr '\0' ' ' < "$dossier/cmdline" 2>/dev/null | grep -q -- "-jar /opt/imagunity/imagunity-box.jar"; then
        PROPRIETAIRE=$(stat -c %U "$dossier")
    fi
done
[ "$PROPRIETAIRE" = imagunity ] || { echo "ÉCHEC : box sous « $PROPRIETAIRE », pas imagunity" >&2; exit 1; }
echo "ok : box démarrée sous imagunity, en bonne santé"
echo "== Installation sur Rocky Linux 10 : tout est conforme"
