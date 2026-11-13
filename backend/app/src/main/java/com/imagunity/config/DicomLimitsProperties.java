package com.imagunity.config;

import com.imagunity.dicom.DicomStoreScp;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Limites de l'entrée DICOM ({@code imagunity.dicom.limits.*}), face à un poste du réseau local malveillant ou
 * défaillant.
 *
 * @param maxInstanceSize plus grande instance acceptée (C-STORE et STOW-RS)
 * @param maxAssociations associations ouvertes en même temps, au plus
 * @param idleTimeout     association sans échange au-delà de ce délai : fermée
 * @param requestTimeout  délai pour recevoir la demande d'association après la connexion
 * @param allowedSources  adresses ou réseaux CIDR admis (vide : tous ; l'AET appelant reste contrôlé)
 */
@ConfigurationProperties("imagunity.dicom.limits")
public record DicomLimitsProperties(
        @DefaultValue("4GB") DataSize maxInstanceSize,
        @DefaultValue("32") int maxAssociations,
        @DefaultValue("2m") Duration idleTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue Set<String> allowedSources) {

    DicomStoreScp.Limits toLimits() {
        return new DicomStoreScp.Limits(maxInstanceSize.toBytes(), maxAssociations, idleTimeout, requestTimeout,
                allowedSources);
    }
}
