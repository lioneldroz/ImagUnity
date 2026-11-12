package com.imagunity.api;

import com.imagunity.domain.inbound.InboundTransfer;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vues JSON des transferts pour la console. Elles ne contiennent aucune donnée démographique du patient :
 * seulement des identifiants DICOM, des tailles et des états. Les chemins de fichiers de la box ne sont pas exposés.
 */
public final class TransferViews {

    private TransferViews() {
    }

    /**
     * Transfert envoyé.
     *
     * @param ackOverdue vrai si le transfert est annoncé depuis plus longtemps que le délai d'accusé
     */
    public record Outbound(UUID id, String studyInstanceUid, String calledAet, String destination, TransferState state,
                           long sizeBytes, int partCount, String errorMessage, Instant createdAt, Instant updatedAt,
                           boolean ackOverdue) {

        static Outbound of(Transfer t, Instant now, Duration ackOverdue) {
            boolean overdue = t.state() == TransferState.ANNOUNCED && t.updatedAt().plus(ackOverdue).isBefore(now);
            return new Outbound(t.id(), t.studyInstanceUid(), t.calledAet(), t.destination(), t.state(), t.sizeBytes(),
                    t.partCount(), t.errorMessage(), t.createdAt(), t.updatedAt(), overdue);
        }
    }

    /** Paquet d'un transfert envoyé. */
    public record Part(int index, int count, String fileName, long size, String sha256, boolean handedOver,
                       boolean uploaded) {

        static Part of(TransferPart p) {
            return new Part(p.index(), p.count(), p.file().getFileName().toString(), p.size(), p.sha256(),
                    p.transportFileId() != null, p.uploaded());
        }
    }

    /** Transfert envoyé et ses paquets. */
    public record OutboundDetail(Outbound transfer, List<Part> parts) {
    }

    /**
     * Transfert reçu d'un partenaire.
     *
     * @param restoredParts paquets déjà restitués au PACS (restitution au fil de l'eau)
     */
    public record Inbound(UUID transferId, String peerName, String studyInstanceUid, int partCount,
                          InboundTransfer.State state, String errorMessage, int restoredInstances, boolean ackSent,
                          Instant createdAt, Instant updatedAt, int restoredParts) {

        static Inbound of(InboundTransfer t) {
            return new Inbound(t.transferId(), t.peerName(), t.studyInstanceUid(), t.partCount(), t.state(),
                    t.errorMessage(), t.restoredInstances(), t.ackSent(), t.createdAt(), t.updatedAt(),
                    t.restoredParts().size());
        }
    }

    /**
     * Indicateurs de la box.
     *
     * @param outbound   transferts envoyés par état
     * @param inbound    transferts reçus par état
     * @param ackOverdue transferts annoncés dont l'accusé tarde
     * @param peers      partenaires appairés
     */
    public record Overview(Map<TransferState, Long> outbound, Map<InboundTransfer.State, Long> inbound,
                           long ackOverdue, int peers) {
    }
}
