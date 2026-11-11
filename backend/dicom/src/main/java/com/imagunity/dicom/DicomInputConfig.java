package com.imagunity.dicom;

import java.nio.file.Path;
import java.time.Duration;
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
 */
public record DicomInputConfig(
        String bindAddress,
        int port,
        Set<String> allowedCallingAets,
        Map<String, String> routes,
        Path storageDir,
        Duration studyIdleTimeout,
        long minFreeSpace) {

    /**
     * Forme admise pour un AET de la configuration : elle sert aussi de nom de dossier,
     * d'où un jeu de caractères plus strict que la norme DICOM.
     */
    private static final Pattern SAFE_AET = Pattern.compile("[A-Za-z0-9_\\-]{1,16}");

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
        });
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
