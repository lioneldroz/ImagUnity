package com.imagunity.configuration;

import com.imagunity.directory.NodeCheck;
import java.time.Instant;
import java.util.List;

/** État de la configuration centralisée appliquée par la box, montré par la console. */
public interface CentralConfigurationStatus {

    /**
     * @param sequence     version appliquée, ou 0 (configuration locale)
     * @param routingRules règles de routage centrales (noms et partenaires), vide si le routage reste local
     * @param groups       groupes de la box, d'après son entrée d'annuaire
     * @param dicom        nœuds et routes DICOM centraux (une ligne chacun), vide si la configuration DICOM reste
     *                     locale
     * @param devices      les mêmes, structurés pour la console (appareils avec ce que la box en constate, routes),
     *                     ou {@code null} si la configuration DICOM reste locale
     */
    record View(boolean enabled, long sequence, Instant issuedAt, String author, String comment,
                Instant appliedAt, boolean priority, boolean routing, List<String> routingRules, long refused,
                String refusal, Instant checkedAt, String error, List<String> groups, List<String> dicom,
                Dicom devices) {

        public View {
            dicom = dicom == null ? List.of() : List.copyOf(dicom);
        }

        /** Sans vue structurée des appareils. */
        public View(boolean enabled, long sequence, Instant issuedAt, String author, String comment,
                    Instant appliedAt, boolean priority, boolean routing, List<String> routingRules, long refused,
                    String refusal, Instant checkedAt, String error, List<String> groups, List<String> dicom) {
            this(enabled, sequence, issuedAt, author, comment, appliedAt, priority, routing, routingRules, refused,
                    refusal, checkedAt, error, groups, dicom, null);
        }

        /** État sans configuration DICOM centrale. */
        public View(boolean enabled, long sequence, Instant issuedAt, String author, String comment,
                    Instant appliedAt, boolean priority, boolean routing, List<String> routingRules, long refused,
                    String refusal, Instant checkedAt, String error, List<String> groups) {
            this(enabled, sequence, issuedAt, author, comment, appliedAt, priority, routing, routingRules, refused,
                    refusal, checkedAt, error, groups, List.of());
        }

        /** Configuration centralisée désactivée. */
        public static View disabled() {
            return new View(false, 0, null, null, null, null, false, false, List.of(), 0, null, null, null,
                    List.of());
        }
    }

    /**
     * Configuration DICOM centrale appliquée, pour la console.
     *
     * @param aet              AET de la box fixé par la configuration, ou {@code null} (AET local)
     * @param echoEveryMinutes intervalle entre deux essais de connexion d'une même destination
     */
    record Dicom(String aet, List<Node> nodes, List<Route> outgoing, List<Route> incoming, long echoEveryMinutes) {

        public Dicom {
            nodes = List.copyOf(nodes);
            outgoing = List.copyOf(outgoing);
            incoming = List.copyOf(incoming);
        }
    }

    /**
     * Appareil du site.
     *
     * @param check ce que la box en a constaté (essais de connexion, envois reçus), ou {@code null}
     */
    record Node(String name, String aet, String host, Integer port, boolean source, boolean destination,
                NodeCheck check) {
    }

    /**
     * Route : envoi (d'appareils vers des box, {@code from} = noms d'appareils, {@code to} = identifiants de box) ou
     * réception (de box vers des PACS, {@code from} = identifiants de box, {@code to} = noms d'appareils). Liste
     * {@code from} vide : toute source.
     *
     * @param calledAet AET appelé exigé (envoi), ou {@code null}
     * @param filter    filtre DICOM en clair, ou {@code null} s'il n'y en a pas
     */
    record Route(String name, List<String> from, String calledAet, String filter, List<String> to) {

        public Route {
            from = List.copyOf(from);
            to = List.copyOf(to);
        }
    }

    View status();
}
