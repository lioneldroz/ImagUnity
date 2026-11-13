#!/bin/sh
# Mise à jour automatique, lancée par imagunity-box-maj.timer sous le compte imagunity-maj (aucun root) : télécharge
# et vérifie une version plus récente (serveur IMAGUNITY_UPDATE_URL, ou dépôt manuel), sans l'exécuter. S'il y en a
# une, redémarre la box (droit accordé par la règle polkit 50-imagunity-maj.rules, pour ce seul service), qui la
# revérifie au démarrage (choisir.sh), puis attend qu'elle réponde. Un transfert en cours reprend après le
# redémarrage, au morceau près.
set -eu
JAVA=${IMAGUNITY_JAVA:-java}
# shellcheck disable=SC2086 # options de la JVM (magasin de certificats d'une autorité interne…)
RESULTAT=$("$JAVA" ${IMAGUNITY_JAVA_OPTS:-} -jar /opt/imagunity/imagunity-release.jar telecharger)
case "$RESULTAT" in
    nouvelle*)
        NUMERO=$(printf '%s\n' "$RESULTAT" | cut -f2)
        echo "Version n° $NUMERO prête : redémarrage de la box"
        systemctl --no-ask-password restart imagunity-box.service
        SANTE="http://${SERVER_ADDRESS:-127.0.0.1}:${IMAGUNITY_HTTP_PORT:-8080}/actuator/health"
        ATTENTE=${IMAGUNITY_MAJ_ATTENTE:-300}
        CHOIX=${IMAGUNITY_CHOIX:-/run/imagunity-choix/choix}
        while [ "$ATTENTE" -gt 0 ]; do
            # Version retenue par le lanceur au redémarrage (celle-ci, ou une plus récente déjà sur la box, livrée
            # par une nouvelle archive), puis box en bonne santé.
            RETENUE=$(cut -f3 "$CHOIX" 2>/dev/null || true)
            case "$RETENUE" in
                '' | *[!0-9]*) RETENUE=0 ;;
            esac
            if [ "$RETENUE" -ge "$NUMERO" ] && curl -sf --max-time 5 "$SANTE" | grep -q '"UP"'; then
                echo "Box redémarrée sur la version n° $RETENUE, en bonne santé"
                exit 0
            fi
            sleep 5
            ATTENTE=$((ATTENTE - 5))
        done
        echo "La box ne répond pas après le passage à la version n° $NUMERO : voir journalctl -u imagunity-box" >&2
        exit 1
        ;;
    a-jour*)
        echo "Box à jour (n° $(printf '%s\n' "$RESULTAT" | cut -f2))"
        ;;
    *)
        echo "Réponse inattendue du lanceur : $RESULTAT" >&2
        exit 1
        ;;
esac
