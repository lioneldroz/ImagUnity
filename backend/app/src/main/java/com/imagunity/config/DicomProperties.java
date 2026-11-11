package com.imagunity.config;

import com.imagunity.dicom.DicomInputConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configuration de l'entrée DICOM ({@code imagunity.dicom.*}).
 *
 * @param enabled            ouvre l'entrée DICOM au démarrage
 * @param bindAddress        adresse d'écoute (vide : toutes les interfaces)
 * @param port               port DICOM
 * @param allowedCallingAets émetteurs locaux autorisés (vide : tout est refusé)
 * @param routes             destinations DICOM et partenaires désignés
 * @param storageDir         dossier de réception
 * @param studyIdleTimeout   délai d'inactivité au-delà duquel une étude est complète
 * @param minFreeSpace       espace disque minimal à conserver
 */
@ConfigurationProperties("imagunity.dicom")
public record DicomProperties(
        @DefaultValue("true") boolean enabled,
        String bindAddress,
        @DefaultValue("11112") int port,
        @DefaultValue Set<String> allowedCallingAets,
        @DefaultValue List<Route> routes,
        @DefaultValue("./data/incoming") Path storageDir,
        @DefaultValue("30s") Duration studyIdleTimeout,
        @DefaultValue("1GB") DataSize minFreeSpace) {

    /**
     * Une destination DICOM : envoyer à {@code calledAet}, c'est désigner {@code destination}.
     *
     * @param calledAet   AET à déclarer comme destination dans le PACS
     * @param destination partenaire destinataire (nom du contact appairé)
     */
    public record Route(String calledAet, String destination) {
    }

    DicomInputConfig toConfig() {
        Map<String, String> routeMap = new LinkedHashMap<>();
        for (Route route : routes) {
            if (routeMap.put(route.calledAet(), route.destination()) != null) {
                throw new IllegalArgumentException("Destination DICOM déclarée deux fois : " + route.calledAet());
            }
        }
        return new DicomInputConfig(bindAddress, port, allowedCallingAets, routeMap, storageDir, studyIdleTimeout,
                minFreeSpace.toBytes());
    }
}
