package com.imagunity.api;

import com.imagunity.configuration.CentralConfigurationStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration centralisée appliquée par la box, en lecture seule (docs/reference/configuration-centralisee.md) : version en
 * vigueur, provenance, dernier refus. Les règles elles-mêmes sont visibles sur {@code /api/v1/priority} (priorité) ;
 * le routage central figure ici par le nom de ses règles.
 *
 * <pre>
 * GET /api/v1/configuration
 * </pre>
 */
@RestController
public class CentralConfigurationController {

    private final ObjectProvider<CentralConfigurationStatus> status;

    public CentralConfigurationController(ObjectProvider<CentralConfigurationStatus> status) {
        this.status = status;
    }

    @GetMapping("/api/v1/configuration")
    public CentralConfigurationStatus.View configuration() {
        CentralConfigurationStatus current = status.getIfAvailable();
        return current == null ? CentralConfigurationStatus.View.disabled() : current.status();
    }
}
