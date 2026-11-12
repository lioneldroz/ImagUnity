package com.imagunity.dicom;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration de l'entrée DICOM de la box.
 *
 * @param bindAddress        adresse d'écoute ({@code null} : toutes les interfaces du réseau local)
 * @param port               port DICOM (11112 par convention)
 * @param allowedCallingAets AET des émetteurs locaux autorisés (PACS, stations). Liste vide : tout est refusé.
 * @param routes             destination DICOM (AET appelé) → partenaire destinataire. Envoyer à une destination,
 *                           c'est désigner le destinataire.
 * @param storageDir         dossier de réception des instances
 * @param studyIdleTimeout   délai sans nouvelle instance au-delà duquel une étude est considérée complète
 * @param minFreeSpace       espace disque minimal à conserver ; en dessous, les instances sont refusées
 * @param routingAet         destination DICOM dont le partenaire est choisi par les règles ({@code null} : aucune)
 * @param rules              règles de routage par champ DICOM, dans l'ordre : la première qui correspond s'applique
 */
public record DicomInputConfig(
        String bindAddress,
        int port,
        Set<String> allowedCallingAets,
        Map<String, String> routes,
        Path storageDir,
        Duration studyIdleTimeout,
        long minFreeSpace,
        String routingAet,
        List<RoutingRule> rules) {

    /**
     * Forme admise pour un AET de la configuration : elle sert aussi de nom de dossier,
     * d'où un jeu de caractères plus strict que la norme DICOM.
     */
    private static final Pattern SAFE_AET = Pattern.compile("[A-Za-z0-9_\\-]{1,16}");
    /** Nom de partenaire (comme les contacts appairés) : sert aussi de nom de dossier. */
    private static final Pattern DESTINATION = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    public DicomInputConfig {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port DICOM invalide : " + port);
        }
        allowedCallingAets = Set.copyOf(allowedCallingAets);
        routes = Map.copyOf(routes);
        Objects.requireNonNull(storageDir, "storageDir");
        Objects.requireNonNull(studyIdleTimeout, "studyIdleTimeout");
        if (studyIdleTimeout.isNegative() || studyIdleTimeout.isZero()) {
            throw new IllegalArgumentException("Le délai d'inactivité d'une étude doit être positif");
        }
        for (String aet : allowedCallingAets) {
            requireAet(aet, "AET appelant autorisé");
        }
        routes.forEach((calledAet, destination) -> {
            requireAet(calledAet, "destination DICOM");
            if (destination == null || destination.isBlank()) {
                throw new IllegalArgumentException("Destination DICOM " + calledAet + " sans partenaire");
            }
            requireDestination(destination, "Destination DICOM " + calledAet);
        });
        rules = rules == null ? List.of() : List.copyOf(rules);
        routingAet = routingAet == null || routingAet.isBlank() ? null : routingAet.strip();
        if (routingAet != null) {
            requireAet(routingAet, "destination DICOM de routage");
            if (routes.containsKey(routingAet)) {
                throw new IllegalArgumentException("Destination DICOM " + routingAet
                        + " déclarée à la fois comme route et comme destination de routage");
            }
            if (rules.isEmpty()) {
                throw new IllegalArgumentException("Destination de routage " + routingAet + " sans règle");
            }
        } else if (!rules.isEmpty()) {
            throw new IllegalArgumentException("Règles de routage sans destination DICOM de routage (routing-aet)");
        }
        if (rules.stream().map(RoutingRule::name).distinct().count() != rules.size()) {
            throw new IllegalArgumentException("Deux règles de routage portent le même nom");
        }
    }

    /** Entrée DICOM sans routage par règles. */
    public DicomInputConfig(String bindAddress, int port, Set<String> allowedCallingAets, Map<String, String> routes,
                            Path storageDir, Duration studyIdleTimeout, long minFreeSpace) {
        this(bindAddress, port, allowedCallingAets, routes, storageDir, studyIdleTimeout, minFreeSpace, null,
                List.of());
    }

    /** Vrai si l'AET appelé est une destination connue : route fixe, ou destination de routage. */
    public boolean isKnownCalledAet(String calledAet) {
        return destinationFor(calledAet).isPresent() || isRoutingAet(calledAet);
    }

    /** Vrai si le partenaire de cette destination DICOM est choisi par les règles. */
    public boolean isRoutingAet(String calledAet) {
        return routingAet != null && calledAet != null && routingAet.equals(calledAet.trim());
    }

    /**
     * Règle qui s'applique à cette instance reçue sur la destination de routage : la première qui correspond.
     *
     * @return vide si aucune règle ne correspond
     */
    public Optional<RoutingRule> ruleFor(org.dcm4che3.data.Attributes dataset) {
        return rules.stream().filter(rule -> rule.matches(dataset)).findFirst();
    }

    static void requireDestination(String destination, String what) {
        if (destination == null || !DESTINATION.matcher(destination).matches()) {
            throw new IllegalArgumentException(what + " : partenaire invalide « " + destination
                    + " » (1 à 64 caractères parmi A-Z, a-z, 0-9, _ et -)");
        }
    }

    /** Vrai si l'émetteur local est autorisé à pousser vers la box. */
    public boolean isCallingAetAllowed(String callingAet) {
        return callingAet != null && allowedCallingAets.contains(callingAet.trim());
    }

    /** Partenaire désigné par la destination DICOM, s'il existe. */
    public Optional<String> destinationFor(String calledAet) {
        return calledAet == null ? Optional.empty() : Optional.ofNullable(routes.get(calledAet.trim()));
    }

    private static void requireAet(String aet, String what) {
        if (aet == null || !SAFE_AET.matcher(aet).matches()) {
            throw new IllegalArgumentException(
                    what + " invalide : « " + aet + " » (1 à 16 caractères parmi A-Z, a-z, 0-9, _ et -)");
        }
    }
}
