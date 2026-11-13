#!/bin/sh
# Vérifie les unités systemd de la box avec systemd-analyze (CI, workflow « CI », tâche « Installations »), dans
# les conditions d'une machine installée : comptes, scripts dans /opt/imagunity/bin, complément TPM, script
# d'alerte du site. À lancer en root sur une machine d'essai (les comptes et /opt/imagunity sont créés).
set -eu
ICI=$(cd "$(dirname "$0")/.." && pwd)
for compte in imagunity imagunity-maj; do getent passwd "$compte" >/dev/null || useradd --system "$compte"; done
getent group tss >/dev/null || groupadd --system tss
install -d /opt/imagunity/bin /etc/imagunity
install -m 0755 "$ICI"/bin/*.sh /opt/imagunity/bin/
[ -e /etc/imagunity/alerte ] || printf '#!/bin/sh\nexit 0\n' > /etc/imagunity/alerte
chmod 0755 /etc/imagunity/alerte
UNITES=$(mktemp -d)
cp "$ICI"/systemd/*.service "$ICI"/systemd/*.timer "$UNITES/"
install -D -m 0644 "$ICI/systemd/imagunity-box-tpm.conf" "$UNITES/imagunity-box.service.d/tpm.conf"
SYSTEMD_UNIT_PATH="$UNITES:" systemd-analyze verify "$UNITES"/*.service "$UNITES"/*.timer
rm -r "$UNITES"
echo "Unités systemd de la box : valides (avec le complément TPM)"
