package com.imagunity.domain.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Transfert annoncé par un partenaire, côté réception.
 *
 * <pre>
 * ANNOUNCED ──chaque paquet reçu, vérifié et restitué au PACS au fil de l'eau ; tous restitués──► RESTORED
 *     └──────────────────────────────── échec ─────────────────────────────────────────────► FAILED
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
 * @param restoredInstances nombre d'instances vérifiées et restituées au PACS (au fil des paquets)
 * @param ackSent          vrai une fois l'accusé confié au transport
 * @param restoredParts    rangs des paquets déjà restitués, dans l'ordre de restitution
 * @param expectedInstances nombre d'instances de l'étude d'après les manifestes, ou -1 avant le premier paquet
 */
public record InboundTransfer(UUID transferId, String peerName, String packageGroupId, String studyInstanceUid,
                              int partCount, String announcement, State state, String errorMessage,
                              Instant createdAt, Instant updatedAt, int restoredInstances, boolean ackSent,
                              List<Integer> restoredParts, int expectedInstances) {

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
        restoredParts = restoredParts == null ? List.of() : List.copyOf(restoredParts);
    }

    /** Transfert sans paquet restitué au fil de l'eau (enregistrement antérieur). */
    public InboundTransfer(UUID transferId, String peerName, String packageGroupId, String studyInstanceUid,
                           int partCount, String announcement, State state, String errorMessage, Instant createdAt,
                           Instant updatedAt, int restoredInstances, boolean ackSent) {
        this(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement, state, errorMessage,
                createdAt, updatedAt, restoredInstances, ackSent, List.of(), -1);
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

    /** Vrai si des paquets sont déjà restitués, mais pas tous. */
    public boolean partiallyRestored() {
        return state == State.ANNOUNCED && !restoredParts.isEmpty();
    }

    /**
     * Un paquet de plus restitué au PACS.
     *
     * @param index     rang du paquet
     * @param instances instances restituées par ce paquet
     * @param expected  instances de l'étude d'après le manifeste du paquet
     */
    public InboundTransfer partRestored(int index, int instances, int expected, Instant now) {
        List<Integer> parts = new java.util.ArrayList<>(restoredParts);
        parts.add(index);
        return new InboundTransfer(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement,
                state, errorMessage, createdAt, now, restoredInstances + instances, ackSent, parts, expected);
    }

    public InboundTransfer restored(int instances, Instant now) {
        return new InboundTransfer(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement,
                State.RESTORED, null, createdAt, now, instances, false, restoredParts, expectedInstances);
    }

    public InboundTransfer failed(String reason, Instant now) {
        // Colonne de 2000 caractères : un motif plus long (texte d'un relais) empêcherait d'enregistrer l'échec.
        String bounded = reason == null || reason.length() <= 1000 ? reason : reason.substring(0, 1000) + "…";
        return new InboundTransfer(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement,
                State.FAILED, bounded, createdAt, now, restoredInstances, false, restoredParts, expectedInstances);
    }

    public InboundTransfer acknowledged(Instant now) {
        return new InboundTransfer(transferId, peerName, packageGroupId, studyInstanceUid, partCount, announcement,
                state, errorMessage, createdAt, now, restoredInstances, true, restoredParts, expectedInstances);
    }
}
