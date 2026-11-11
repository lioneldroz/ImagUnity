package com.imagunity.config;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Signale au démarrage une console ou une entrée DICOM qui écoute sur toutes les interfaces.
 *
 * <p>La box n'a besoin d'aucune connexion entrante depuis Internet : sa console (HTTP) et son entrée DICOM
 * doivent écouter sur l'interface du réseau local seulement ({@code SERVER_ADDRESS},
 * {@code IMAGUNITY_DICOM_BIND_ADDRESS}), en plus du pare-feu du site.
 */
@Component
class ExposureCheck {

    private static final Logger LOG = LoggerFactory.getLogger(ExposureCheck.class);

    private final Environment environment;
    private final DicomProperties dicom;

    ExposureCheck(Environment environment, DicomProperties dicom) {
        this.environment = environment;
        this.dicom = dicom;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        exposedListeners().forEach(listener -> LOG.warn(
                "{} écoute sur toutes les interfaces : la réserver au réseau local ({})", listener[0], listener[1]));
    }

    /** Écoutes ouvertes sur toutes les interfaces : [description, réglage à faire]. */
    List<String[]> exposedListeners() {
        List<String[]> exposed = new ArrayList<>();
        if (isWildcard(environment.getProperty("server.address"))) {
            exposed.add(new String[] {"La console (HTTP)", "SERVER_ADDRESS"});
        }
        if (dicom.enabled() && isWildcard(dicom.bindAddress())) {
            exposed.add(new String[] {"L'entrée DICOM", "IMAGUNITY_DICOM_BIND_ADDRESS"});
        }
        return exposed;
    }

    static boolean isWildcard(String address) {
        return address == null || address.isBlank() || address.equals("0.0.0.0") || address.equals("::")
                || address.equals("[::]");
    }
}
