package com.imagunity.directory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Entrée d'une box dans l'annuaire, signée par le responsable de l'organisation.
 *
 * @param suite            {@code IU1}
 * @param organization     organisation
 * @param boxId            identifiant stable de la box
 * @param displayName      nom affiché
 * @param signingKeys      clés de signature de la box
 * @param transport        adresses de transport (par exemple {@code simplex} : adresse de contact SimpleX)
 * @param serial           numéro d'émission ; seule l'entrée de numéro le plus haut compte
 * @param validFrom        début de validité
 * @param validUntil       fin de validité (exclue)
 * @param revoked          vrai si la box est révoquée
 * @param revokedAt        date de la révocation
 * @param revocationReason motif de la révocation
 */
public record BoxEntry(String suite, String organization, UUID boxId, String displayName,
                       HybridPublicKey.Encoded signingKeys, Map<String, String> transport, long serial,
                       Instant validFrom, Instant validUntil, boolean revoked, Instant revokedAt,
                       String revocationReason) {

    public BoxEntry {
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(boxId, "boxId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(signingKeys, "signingKeys");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        transport = transport == null ? Map.of() : Map.copyOf(transport);
        if (serial < 1) {
            throw new IllegalArgumentException("Le numéro d'émission commence à 1");
        }
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("Fin de validité antérieure au début");
        }
    }

    /** Clés de signature de la box. */
    public HybridPublicKey signingKey() {
        return HybridPublicKey.decode(signingKeys);
    }

    /** État de l'entrée à cette date. */
    public Status statusAt(Instant now) {
        if (revoked) {
            return Status.REVOKED;
        }
        if (now.isBefore(validFrom)) {
            return Status.NOT_YET_VALID;
        }
        return now.isBefore(validUntil) ? Status.ACTIVE : Status.EXPIRED;
    }

    /** Même box, révoquée : numéro suivant. */
    BoxEntry revoke(Instant at, String reason) {
        return new BoxEntry(suite, organization, boxId, displayName, signingKeys, transport, serial + 1, validFrom,
                validUntil, true, at, reason);
    }

    /** État d'une entrée pour les échanges. */
    public enum Status {
        /** Valide : on peut échanger avec cette box. */
        ACTIVE,
        /** Révoquée par le responsable. */
        REVOKED,
        /** Validité terminée : à renouveler par le responsable. */
        EXPIRED,
        /** Pas encore valide. */
        NOT_YET_VALID
    }
}
