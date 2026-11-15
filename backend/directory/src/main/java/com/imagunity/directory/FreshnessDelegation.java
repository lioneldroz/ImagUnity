package com.imagunity.directory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Objects;

/**
 * Délégation de la clé de fraîcheur : le responsable autorise, pour une durée courte, une clé en ligne (sur le service
 * d'annuaire) à signer les preuves de fraîcheur de son organisation. Signée par la clé du responsable.
 *
 * @param key clé de fraîcheur déléguée
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreshnessDelegation(String suite, String type, String organization, HybridPublicKey.Encoded key,
                                  long serial, Instant validFrom, Instant validUntil) {

    public static final String TYPE = "freshness-key";

    public FreshnessDelegation {
        Organization.requireValidId(organization);
        Objects.requireNonNull(key, "key");
        HybridPublicKey.decode(key);
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        if (!TYPE.equals(type)) {
            throw new IllegalArgumentException("Document d'un autre type que « freshness-key » : " + type);
        }
        if (serial < 1) {
            throw new IllegalArgumentException("Le numéro d'émission commence à 1");
        }
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("Fin de validité antérieure au début");
        }
    }

    public HybridPublicKey publicKey() {
        return HybridPublicKey.decode(key);
    }

    /** Vrai si la délégation couvre cet instant. */
    public boolean coversAt(Instant at) {
        return !at.isBefore(validFrom) && at.isBefore(validUntil);
    }
}
