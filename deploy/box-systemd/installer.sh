#!/bin/sh
# Installe (ou met à jour) le lanceur et la version livrée de la box ImagUnity sur Rocky Linux 10, sous systemd.
# À lancer en root depuis le dossier de l'archive. Ne touche ni à la configuration ni aux données existantes.
set -eu
ICI=$(cd "$(dirname "$0")" && pwd)
[ "$(id -u)" = 0 ] || { echo "À lancer en root" >&2; exit 1; }
JAVA=${IMAGUNITY_JAVA:-java}
if ! "$JAVA" -version 2>&1 | grep -qE 'version "(2[5-9]|[3-9][0-9])'; then
    echo "Java 25 ou plus récent est requis (voir le guide, § Java)" >&2
    exit 1
fi
for f in imagunity-release.jar imagunity-box.jar imagunity-box.jar.manifeste.json; do
    [ -f "$ICI/$f" ] || { echo "Fichier absent de l'archive : $f" >&2; exit 1; }
done

# Compte de service sans privilège ni shell.
getent group imagunity >/dev/null || groupadd --system imagunity
getent passwd imagunity >/dev/null || useradd --system --gid imagunity --home-dir /var/lib/imagunity-box \
    --no-create-home --shell /sbin/nologin --comment "ImagUnity box" imagunity

# Lanceur, version livrée et scripts : à root, en lecture seule pour la box.
install -d -m 0755 -o root -g root /opt/imagunity /opt/imagunity/bin
install -m 0644 -o root -g root "$ICI/imagunity-release.jar" "$ICI/imagunity-box.jar" \
    "$ICI/imagunity-box.jar.manifeste.json" /opt/imagunity/
install -m 0755 -o root -g root "$ICI"/bin/*.sh /opt/imagunity/bin/

# Versions installées par les mises à jour, et état anti-retour : à root.
install -d -m 0755 -o root -g root /var/lib/imagunity-maj

# Configuration : créée une fois, jamais écrasée.
install -d -m 0750 -o root -g imagunity /etc/imagunity
if [ ! -f /etc/imagunity/box.env ]; then
    install -m 0640 -o root -g imagunity "$ICI/box.env.exemple" /etc/imagunity/box.env
    echo "Configuration à compléter : /etc/imagunity/box.env"
fi
if [ ! -f /etc/imagunity/phrase-box ]; then
    (umask 077; head -c 24 /dev/urandom | base64 > /etc/imagunity/phrase-box)
    echo "Phrase de passe des clés de la box créée : /etc/imagunity/phrase-box"
fi
chown root:root /etc/imagunity/phrase-box
chmod 0600 /etc/imagunity/phrase-box

# Services systemd.
install -m 0644 -o root -g root "$ICI"/systemd/*.service "$ICI"/systemd/*.timer /etc/systemd/system/
install -m 0644 -o root -g root "$ICI/systemd/imagunity-box-tpm.conf" /opt/imagunity/
systemctl daemon-reload
echo "Installé : lanceur et version livrée dans /opt/imagunity, services dans /etc/systemd/system"
echo "Suite : compléter /etc/imagunity/box.env, puis « systemctl enable --now imagunity-box imagunity-box-maj.timer »"
