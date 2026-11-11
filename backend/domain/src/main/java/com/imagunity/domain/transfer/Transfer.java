package com.imagunity.domain.transfer;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transfert d'une étude vers un partenaire, côté émetteur.
 *
 * <p>Immuable : chaque changement produit une nouvelle valeur, enregistrée par le
 * {@link TransferRepository}. Les changements d'état respectent {@link TransferState#canTransitionTo}.
 *
 * @param id               identifiant du transfert
 * @param studyInstanceUid étude transférée
 * @param calledAet        destination DICOM sur laquelle l'étude a été reçue
 * @param destination      partenaire destinataire (nom local, cf. {@code Peer})
 * @param state            état courant
 * @param sizeBytes        taille de l'étude, en octets
 * @param packageGroupId   identifiant des paquets de l'étude, une fois empaquetée ({@code null} avant)
 * @param partCount        nombre de paquets (0 avant l'empaquetage)
 * @param errorMessage     cause de l'échec, en état {@link TransferState#FAILED}
 * @param createdAt        création du transfert
 * @param updatedAt        dernier changement
 */
public record Transfer(
        UUID id,
        String studyInstanceUid,
        String calledAet,
        String destination,
        TransferState state,
        long sizeBytes,
        String packageGroupId,
        int partCount,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public Transfer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studyInstanceUid, "studyInstanceUid");
        Objects.requireNonNull(calledAet, "calledAet");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Nouveau transfert, à l'état {@link TransferState#RECEIVED}. */
    public static Transfer received(String studyInstanceUid, String calledAet, String destination, long sizeBytes,
                                    Instant now) {
        return new Transfer(UUID.randomUUID(), studyInstanceUid, calledAet, destination, TransferState.RECEIVED,
                sizeBytes, null, 0, null, now, now);
    }

    /**
     * Passe à l'état {@code next} ; le message d'erreur est effacé, sauf vers {@link TransferState#FAILED}
     * (utiliser {@link #failed}).
     *
     * @throws IllegalStateException si la transition n'est pas autorisée
     */
    public Transfer moveTo(TransferState next, Instant now) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("Transition interdite : " + state + " → " + next);
        }
        return new Transfer(id, studyInstanceUid, calledAet, destination, next, sizeBytes, packageGroupId,
                partCount, null, createdAt, now);
    }

    /** Passe à l'état {@link TransferState#FAILED} avec sa cause. */
    public Transfer failed(String reason, Instant now) {
        Transfer moved = moveTo(TransferState.FAILED, now);
        return new Transfer(id, studyInstanceUid, calledAet, destination, moved.state(), sizeBytes, packageGroupId,
                partCount, reason, createdAt, now);
    }

    /** Enregistre le résultat de l'empaquetage et passe à {@link TransferState#PACKAGED}. */
    public Transfer packaged(String groupId, int parts, Instant now) {
        Transfer moved = moveTo(TransferState.PACKAGED, now);
        return new Transfer(id, studyInstanceUid, calledAet, destination, moved.state(), sizeBytes, groupId, parts,
                null, createdAt, now);
    }
}
