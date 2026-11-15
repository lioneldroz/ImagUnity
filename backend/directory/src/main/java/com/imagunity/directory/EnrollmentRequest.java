package com.imagunity.directory;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param encryptionKeys clés de chiffrement de la box ({@code null} : demande antérieure au chiffrement applicatif)
 * @param transport   adresses de transport
 * @param requestedAt date de la demande
 * @param protocols   versions du protocole que parle la box (absentes : {@code iu1.0}), recopiées dans son entrée
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record EnrollmentRequest(String suite, UUID boxId, String displayName, HybridPublicKey.Encoded signingKeys,
                                @JsonInclude(JsonInclude.Include.NON_NULL)
                                HybridKemPublicKey.Encoded encryptionKeys,
                                Map<String, String> transport, Instant requestedAt,
                                @JsonInclude(JsonInclude.Include.NON_EMPTY) java.util.List<String> protocols) {

    public EnrollmentRequest {
        Objects.requireNonNull(boxId, "boxId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(signingKeys, "signingKeys");
        Objects.requireNonNull(requestedAt, "requestedAt");
        transport = transport == null ? Map.of() : Map.copyOf(transport);
        protocols = Protocols.of(protocols);
    }

    /** Demande sans version de protocole annoncée. */
    public EnrollmentRequest(String suite, UUID boxId, String displayName, HybridPublicKey.Encoded signingKeys,
                             HybridKemPublicKey.Encoded encryptionKeys, Map<String, String> transport,
                             Instant requestedAt) {
        this(suite, boxId, displayName, signingKeys, encryptionKeys, transport, requestedAt, java.util.List.of());
    }

    /** Demande sans clés de chiffrement. */
    public EnrollmentRequest(String suite, UUID boxId, String displayName, HybridPublicKey.Encoded signingKeys,
                             Map<String, String> transport, Instant requestedAt) {
        this(suite, boxId, displayName, signingKeys, null, transport, requestedAt);
    }

    /** Demande signée par la box, sans clés de chiffrement. */
    public static SignedDocument create(HybridKeyPair boxKeys, UUID boxId, String displayName,
                                        Map<String, String> transport, Instant now) {
        return create(boxKeys, null, boxId, displayName, transport, now);
    }

    /**
     * Demande signée par la box : la signature lie aussi les clés de chiffrement à la box.
     *
     * @param encryption clés de chiffrement de la box ({@code null} : aucune)
     */
    public static SignedDocument create(HybridKeyPair boxKeys, HybridKemPublicKey encryption, UUID boxId,
                                        String displayName, Map<String, String> transport, Instant now) {
        return create(boxKeys, encryption, boxId, displayName, transport, now, Protocols.SUPPORTED);
    }

    /** Demande signée par la box, qui annonce ces versions du protocole (celles de son logiciel par défaut). */
    public static SignedDocument create(HybridKeyPair boxKeys, HybridKemPublicKey encryption, UUID boxId,
                                        String displayName, Map<String, String> transport, Instant now,
                                        java.util.List<String> protocols) {
        EnrollmentRequest request = new EnrollmentRequest(Suite.ID, boxId, displayName,
                boxKeys.publicKey().encoded(), encryption == null ? null : encryption.encoded(), transport, now,
                protocols);
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
        if (request.encryptionKeys() != null) {
            HybridKemPublicKey.decode(request.encryptionKeys()); // clés lisibles
        }
        return request;
    }
}
