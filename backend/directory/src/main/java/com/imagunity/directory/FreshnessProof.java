package com.imagunity.directory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Preuve de fraîcheur d'un annuaire : état daté, signé par la clé de fraîcheur déléguée, du plus haut numéro de chaque
 * entrée de box et de chaque reconnaissance publiée. Une box qui détient un numéro inférieur sait qu'une version plus
 * récente existe (une révocation, peut-être) ; une preuve trop ancienne signale un annuaire figé.
 *
 * @param entries      plus haut numéro publié de chaque box
 * @param recognitions plus haut numéro publié de chaque reconnaissance, par empreinte du partenaire
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreshnessProof(String suite, String type, String organization, Instant issuedAt,
                             Map<UUID, Long> entries, Map<String, Long> recognitions) {

    public static final String TYPE = "freshness";

    public FreshnessProof {
        Organization.requireValidId(organization);
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (!TYPE.equals(type)) {
            throw new IllegalArgumentException("Document d'un autre type que « freshness » : " + type);
        }
        entries = entries == null ? Map.of() : Map.copyOf(new TreeMap<>(entries));
        recognitions = recognitions == null ? Map.of() : Map.copyOf(new TreeMap<>(recognitions));
    }

    /** Signe l'état de l'annuaire avec la clé de fraîcheur (service d'annuaire). */
    public static SignedDocument sign(HybridKeyPair freshnessKey, String organization, Instant issuedAt,
                                      Map<UUID, Long> entries, Map<String, Long> recognitions) {
        return SignedDocument.sign(Json.write(new FreshnessProof(Suite.ID, TYPE, organization, issuedAt,
                new TreeMap<>(entries), new TreeMap<>(recognitions))), freshnessKey);
    }
}
