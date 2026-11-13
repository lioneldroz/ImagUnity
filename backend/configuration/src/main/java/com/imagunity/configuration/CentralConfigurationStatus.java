package com.imagunity.configuration;

import java.time.Instant;
import java.util.List;

/** État de la configuration centralisée appliquée par la box, montré par la console. */
public interface CentralConfigurationStatus {

    /**
     * @param sequence     version appliquée, ou 0 (configuration locale)
     * @param routingRules règles de routage centrales (noms et partenaires), vide si le routage reste local
     * @param groups       groupes de la box, d'après son entrée d'annuaire
     */
    record View(boolean enabled, long sequence, Instant issuedAt, String author, String comment,
                Instant appliedAt, boolean priority, boolean routing, List<String> routingRules, long refused,
                String refusal, Instant checkedAt, String error, List<String> groups) {

        /** Configuration centralisée désactivée. */
        public static View disabled() {
            return new View(false, 0, null, null, null, null, false, false, List.of(), 0, null, null, null,
                    List.of());
        }
    }

    View status();
}
