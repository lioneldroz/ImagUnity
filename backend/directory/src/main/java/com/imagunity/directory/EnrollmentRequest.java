package com.imagunity.directory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Demande d'enrôlement, signée par la box avec ses propres clés : cette signature prouve qu'elle détient les clés
 * privées correspondant aux clés publiques demandées.
 *
 * @param suite       {@code IU1}
 * @param boxId       identifiant de la box
 * @param displayName nom proposé
 * @param signingKeys clés de signature de la box
 * @param transport   adresses de transport
 * @param requestedAt date de la demande
 */
public record EnrollmentRequest(String suite, UUID boxId, String displayName, HybridPublicKey.Encoded signingKeys,
                                Map<String, String> transport, Instant requestedAt) {

    public EnrollmentRequest {
        Objects.requireNonNull(boxId, "boxId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(signingKeys, "signingKeys");
        Objects.requireNonNull(requestedAt, "requestedAt");
        transport = transport == null ? Map.of() : Map.copyOf(transport);
    }

    /** Demande signée par la box. */
    public static SignedDocument create(HybridKeyPair boxKeys, UUID boxId, String displayName,
                                        Map<String, String> transport, Instant now) {
        EnrollmentRequest request = new EnrollmentRequest(Suite.ID, boxId, displayName,
                boxKeys.publicKey().encoded(), transport, now);
        return SignedDocument.sign(Json.write(request), boxKeys);
    }

    /**
     * Lit une demande et vérifie qu'elle est signée par les clés qu'elle contient (preuve de possession).
     *
     * @throws DirectoryException si la demande est illisible ou mal signée
     */
    public static EnrollmentRequest verify(SignedDocument document) {
        EnrollmentRequest request = Json.read(document.payload(), EnrollmentRequest.class, "Demande d'enrôlement");
        Suite.require(request.suite());
        if (!document.isSignedBy(HybridPublicKey.decode(request.signingKeys()))) {
            throw new DirectoryException("Demande d'enrôlement non signée par les clés qu'elle présente");
        }
        return request;
    }
}
