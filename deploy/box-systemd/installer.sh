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

# Deux comptes de service sans privilège ni shell : imagunity exécute la box ; imagunity-maj vérifie et installe les
# versions signées, et peut seulement redémarrer la box (règle polkit). Aucun processus ne tourne en root.
getent group imagunity >/dev/null || groupadd --system imagunity
getent passwd imagunity >/dev/null || useradd --system --gid imagunity --home-dir /var/lib/imagunity-box \
    --no-create-home --shell /sbin/nologin --comment "ImagUnity box" imagunity
getent group imagunity-maj >/dev/null || groupadd --system imagunity-maj
getent passwd imagunity-maj >/dev/null || useradd --system --gid imagunity-maj --home-dir /var/lib/imagunity-maj \
    --no-create-home --shell /sbin/nologin --comment "ImagUnity mises a jour" imagunity-maj
if ! command -v pkcheck >/dev/null 2>&1; then
    echo "polkit est requis pour la mise à jour automatique : dnf -y install polkit" >&2
    exit 1
fi

# Lanceur, version livrée et scripts : à root, en lecture seule pour la box.
install -d -m 0755 -o root -g root /opt/imagunity /opt/imagunity/bin
install -m 0644 -o root -g root "$ICI/imagunity-release.jar" "$ICI/imagunity-box.jar" \
    "$ICI/imagunity-box.jar.manifeste.json" /opt/imagunity/
install -m 0755 -o root -g root "$ICI"/bin/*.sh /opt/imagunity/bin/

# Versions installées par les mises à jour, et état anti-retour : au compte imagunity-maj, lisibles par la box.
install -d -m 0755 -o imagunity-maj -g imagunity-maj /var/lib/imagunity-maj /var/lib/imagunity-maj/depot
chown -R imagunity-maj:imagunity-maj /var/lib/imagunity-maj      # installation antérieure, en root

# Configuration : créée une fois, jamais écrasée. box.env et la phrase de passe ne sont lus que par systemd, qui les
# transmet aux services ; les clés publiques et le magasin de confiance sont lisibles par les deux comptes.
install -d -m 0755 -o root -g root /etc/imagunity
if [ ! -f /etc/imagunity/box.env ]; then
    install -m 0600 -o root -g root "$ICI/box.env.exemple" /etc/imagunity/box.env
    echo "Configuration à compléter : /etc/imagunity/box.env"
fi
chown root:root /etc/imagunity/box.env
chmod 0600 /etc/imagunity/box.env
if [ ! -f /etc/imagunity/phrase-box ]; then
    (umask 077; head -c 24 /dev/urandom | base64 > /etc/imagunity/phrase-box)
    echo "Phrase de passe des clés de la box créée : /etc/imagunity/phrase-box"
fi
chown root:root /etc/imagunity/phrase-box
chmod 0600 /etc/imagunity/phrase-box

# Services systemd.
install -m 0644 -o root -g root "$ICI"/systemd/*.service "$ICI"/systemd/*.timer /etc/systemd/system/
install -m 0644 -o root -g root "$ICI/systemd/imagunity-box-tpm.conf" /opt/imagunity/
install -d -m 0755 /etc/polkit-1/rules.d
install -m 0644 -o root -g root "$ICI/polkit/50-imagunity-maj.rules" /etc/polkit-1/rules.d/
systemctl daemon-reload
echo "Installé : lanceur et version livrée dans /opt/imagunity, services dans /etc/systemd/system"
echo "Suite : compléter /etc/imagunity/box.env, puis « systemctl enable --now imagunity-box imagunity-box-maj.timer »"
