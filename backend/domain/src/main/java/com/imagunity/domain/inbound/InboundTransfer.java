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
 *
 * @param transferId       identifiant du transfert chez l'émetteur (repris dans l'accusé, phase 6)
 * @param peerName         partenaire émetteur (nom local)
 * @param packageGroupId   groupe des paquets annoncés
 * @param studyInstanceUid étude annoncée
 * @param partCount        nombre de paquets annoncés
 * @param announcement     annonce reçue (JSON), conservée pour la vérification des paquets
 * @param state            état
 * @param errorMessage     cause de l'échec
 * @param createdAt        réception de l'annonce
 * @param updatedAt        dernier changement
 */
public record InboundTransfer(UUID transferId, String peerName, String packageGroupId, String studyInstanceUid,
                              int partCount, String announcement, State state, String errorMessage,
                              Instant createdAt, Instant updatedAt) {

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

    public InboundTransfer restored(Instant now) {
        return new InboundTransfer(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement,
                State.RESTORED, null, createdAt, now);
    }

    public InboundTransfer failed(String reason, Instant now) {
        return new InboundTransfer(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement,
                State.FAILED, reason, createdAt, now);
    }
}
