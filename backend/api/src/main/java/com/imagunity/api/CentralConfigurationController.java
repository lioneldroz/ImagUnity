package com.imagunity.api;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration centralisée appliquée par la box, en lecture seule (docs/configuration-centralisee.md) : version en
 * vigueur, provenance, dernier refus. Les règles elles-mêmes sont visibles sur {@code /api/v1/priority} (priorité) ;
 * le routage central figure ici par le nom de ses règles.
 *
 * <pre>
 * GET /api/v1/configuration
 * </pre>
 */
@RestController
public class CentralConfigurationController {

    /** État de la configuration centralisée, fourni par la box. */
    public interface Status {

        /**
         * @param sequence     version appliquée, ou 0 (configuration locale)
         * @param routingRules règles de routage centrales (noms et partenaires), vide si le routage reste local
         * @param groups       groupes de la box, d'après son entrée d'annuaire
         */
        record View(boolean enabled, long sequence, Instant issuedAt, String author, String comment,
                    Instant appliedAt, boolean priority, boolean routing, List<String> routingRules, long refused,
                    String refusal, Instant checkedAt, String error, List<String> groups) {
        }

        View status();
    }

    private final ObjectProvider<Status> status;

    public CentralConfigurationController(ObjectProvider<Status> status) {
        this.status = status;
    }

    @GetMapping("/api/v1/configuration")
    public Status.View configuration() {
        Status current = status.getIfAvailable();
        return current == null ? new Status.View(false, 0, null, null, null, null, false, false, List.of(), 0, null,
                null, null, List.of()) : current.status();
    }
}
