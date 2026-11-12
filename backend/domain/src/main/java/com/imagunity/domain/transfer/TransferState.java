package com.imagunity.domain.transfer;

import java.util.Set;

/**
 * États d'un transfert d'examen, côté émetteur.
 *
 * <p>Chaque état est persisté : au redémarrage, la box reprend les transferts
 * là où ils se sont arrêtés.
 */
public enum TransferState {

    /** L'examen a été reçu du PACS local et l'étude est complète. */
    RECEIVED,
    /** L'examen est compressé, découpé et son manifeste est calculé. */
    PACKAGED,
    /** Les morceaux sont déposés sur le relais IU-T (transferts annoncés après le dépôt, avant la v0.10). */
    UPLOADED,
    /**
     * Le transfert est annoncé au destinataire (annonce signée), dès que tous ses paquets sont confiés au transport :
     * le destinataire les reçoit et les restitue au fil de l'eau pendant le dépôt.
     */
    ANNOUNCED,
    /** Le destinataire a confirmé que l'examen est arrivé complet et identique. */
    ACKNOWLEDGED,
    /** Le transfert a échoué ; il peut être relancé depuis l'étape en cause. */
    FAILED;

    /** Vrai si l'état est final et n'évoluera plus sans intervention. */
    public boolean isTerminal() {
        return this == ACKNOWLEDGED || this == FAILED;
    }

    /** Vrai si la transition de cet état vers {@code next} est autorisée. */
    public boolean canTransitionTo(TransferState next) {
        return allowedNext().contains(next);
    }

    private Set<TransferState> allowedNext() {
        return switch (this) {
            case RECEIVED -> Set.of(PACKAGED, FAILED);
            case PACKAGED -> Set.of(UPLOADED, ANNOUNCED, FAILED);
            case UPLOADED -> Set.of(ANNOUNCED, FAILED);
            case ANNOUNCED -> Set.of(ACKNOWLEDGED, FAILED);
            case ACKNOWLEDGED -> Set.of();
            case FAILED -> Set.of(RECEIVED, PACKAGED, UPLOADED, ANNOUNCED);
        };
    }
}
