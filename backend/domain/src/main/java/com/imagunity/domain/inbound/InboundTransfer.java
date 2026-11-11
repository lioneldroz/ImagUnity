package com.imagunity.domain.inbound;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transfert annoncé par un partenaire, côté réception.
 *
 * <pre>
 * ANNOUNCED ──paquets reçus, vérifiés, restitués au PACS──► RESTORED
 *     └──────────────────── échec ────────────────────────► FAILED
 * </pre>
 * Dans les deux états finaux, un accusé ({@code imagunity.transfer.ack}) est envoyé à l'émetteur ;
 * {@code ackSent} indique qu'il est parti, pour le renvoyer après un redémarrage sinon.
 *
 * @param transferId       identifiant du transfert chez l'émetteur (repris dans l'accusé)
 * @param peerName         partenaire émetteur (nom local)
 * @param packageGroupId   groupe des paquets annoncés
 * @param studyInstanceUid étude annoncée
 * @param partCount        nombre de paquets annoncés
 * @param announcement     annonce reçue (JSON), conservée pour la vérification des paquets
 * @param state            état
 * @param errorMessage     cause de l'échec
 * @param createdAt        réception de l'annonce
 * @param updatedAt        dernier changement
 * @param restoredInstances nombre d'instances vérifiées et restituées au PACS (0 avant la restitution)
 * @param ackSent          vrai une fois l'accusé confié au transport
 */
public record InboundTransfer(UUID transferId, String peerName, String packageGroupId, String studyInstanceUid,
                              int partCount, String announcement, State state, String errorMessage,
                              Instant createdAt, Instant updatedAt, int restoredInstances, boolean ackSent) {

    public enum State {
        ANNOUNCED,
        RESTORED,
        FAILED
    }

    public InboundTransfer {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(peerName, "peerName");
        Objects.requireNonNull(packageGroupId, "packageGroupId");
        Objects.requireNonNull(studyInstanceUid, "studyInstanceUid");
        Objects.requireNonNull(announcement, "announcement");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Transfert sans instance restituée ni accusé envoyé (annonce reçue). */
    public InboundTransfer(UUID transferId, String peerName, String packageGroupId, String studyInstanceUid,
                           int partCount, String announcement, State state, String errorMessage, Instant createdAt,
                           Instant updatedAt) {
        this(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement, state, errorMessage,
                createdAt, updatedAt, 0, false);
    }

    /** Vrai si le transfert est terminé ({@code RESTORED} ou {@code FAILED}) et que l'accusé reste à envoyer. */
    public boolean awaitsAck() {
        return state != State.ANNOUNCED && !ackSent;
    }

    public InboundTransfer restored(int instances, Instant now) {
        return new InboundTransfer(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement,
                State.RESTORED, null, createdAt, now, instances, false);
    }

    public InboundTransfer failed(String reason, Instant now) {
        return new InboundTransfer(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement,
                State.FAILED, reason, createdAt, now, 0, false);
    }

    public InboundTransfer acknowledged(Instant now) {
        return new InboundTransfer(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement,
                state, errorMessage, createdAt, now, restoredInstances, true);
    }
}
