package com.imagunity.config;

import com.imagunity.dicom.DicomInputConfig;
import com.imagunity.dicom.RoutingRule;
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
 * @param routingAet         destination DICOM dont le partenaire est choisi par les règles (vide : aucune)
 * @param rules              règles de routage par champ DICOM, dans l'ordre : la première qui correspond s'applique
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
        @DefaultValue("1GB") DataSize minFreeSpace,
        String routingAet,
        @DefaultValue List<Rule> rules) {

    /** Constructeur utilisé pour lier la configuration. */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DicomProperties {
    }

    /** Entrée DICOM sans routage par règles. */
    public DicomProperties(boolean enabled, String bindAddress, int port, Set<String> allowedCallingAets,
                           List<Route> routes, Path storageDir, Duration studyIdleTimeout, DataSize minFreeSpace) {
        this(enabled, bindAddress, port, allowedCallingAets, routes, storageDir, studyIdleTimeout, minFreeSpace, null,
                List.of());
    }

    /**
     * Règle de routage par champ DICOM.
     *
     * @param name nom de la règle (journaux)
     * @param when conditions : mot-clé DICOM → motif ({@code *}, {@code ?}, {@code !} pour la négation)
     * @param to   partenaires destinataires
     */
    public record Rule(String name, Map<String, String> when, List<String> to) {
    }

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
        List<RoutingRule> routingRules = rules == null ? List.of() : rules.stream()
                .map(rule -> new RoutingRule(rule.name(), rule.when(), rule.to())).toList();
        return new DicomInputConfig(bindAddress, port, allowedCallingAets, routeMap, storageDir, studyIdleTimeout,
                minFreeSpace.toBytes(), routingAet, routingRules);
    }
}
