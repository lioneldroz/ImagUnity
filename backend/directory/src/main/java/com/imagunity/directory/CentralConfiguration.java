package com.imagunity.directory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Document de configuration centralisée (docs/reference/configuration-centralisee.md) : règles de priorité et de routage des
 * box, signées par le responsable de l'organisation, publiées par le service d'annuaire et appliquées par chaque box
 * visée, sans redémarrage.
 *
 * <ul>
 *   <li>Signé comme les entrées de l'annuaire (suite {@code IU1}, octets exacts) par la clé du responsable, qui reste
 *       hors ligne.</li>
 *   <li>{@code sequence} croît pour toute l'organisation : une box applique le document de numéro le plus haut qui la
 *       vise, et refuse tout document plus ancien que celui qu'elle applique (pas de retour en arrière imposé).</li>
 *   <li>{@code type} distingue ce document d'une entrée de box : l'un ne peut pas passer pour l'autre.</li>
 * </ul>
 *
 * @param type         {@link #TYPE}
 * @param suite        {@code IU1}
 * @param organization organisation
 * @param target       box visées
 * @param sequence     numéro de version, croissant pour l'organisation
 * @param issuedAt     date d'émission
 * @param author       auteur du changement (pour l'audit), facultatif
 * @param comment      motif du changement, facultatif
 * @param priority     règles de priorité des séries, ou {@code null} : configuration locale de la box
 * @param routing      règles de routage par champ DICOM, ou {@code null} : configuration locale de la box
 * @param dicom        nœuds DICOM du site et routes source → destination, ou {@code null} : configuration locale
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record CentralConfiguration(String type, String suite, String organization, Target target, long sequence,
                                   Instant issuedAt,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String author,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String comment,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Priority priority,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Routing routing,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Dicom dicom) {

    /** Document sans bloc DICOM (antérieur aux nœuds et routes centralisés). */
    public CentralConfiguration(String type, String suite, String organization, Target target, long sequence,
                                Instant issuedAt, String author, String comment, Priority priority, Routing routing) {
        this(type, suite, organization, target, sequence, issuedAt, author, comment, priority, routing, null);
    }

    /**
     * Nœud DICOM du site de la box : PACS, modalité, station.
     *
     * @param name        nom du nœud dans la configuration (minuscules, chiffres, tirets)
     * @param aet         son AET
     * @param host        son adresse : obligatoire pour une destination ; pour une source, l'association doit en venir
     * @param port        son port DICOM (destination)
     * @param source      il peut envoyer à la box
     * @param destination la box peut lui restituer des examens reçus
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String name, String aet, @JsonInclude(JsonInclude.Include.NON_NULL) String host,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer port, Boolean source, Boolean destination) {

        /** Rôle absent : non. */
        public Node {
            source = Boolean.TRUE.equals(source);
            destination = Boolean.TRUE.equals(destination);
        }
    }

    /**
     * Route d'une source vers des destinations, avec un filtre DICOM facultatif ({@code and}, {@code or}).
     * <ul>
     *   <li>sortante : {@code from} = noms de nœuds sources (vide : toutes), {@code calledAet} = AET appelé par la
     *       source (facultatif), {@code to} = identifiants des box destinataires ;</li>
     *   <li>entrante : {@code from} = identifiants des box émettrices (vide : toutes), {@code to} = noms de nœuds
     *       destinations.</li>
     * </ul>
     * La première route qui correspond s'applique.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(String name,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> from,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String calledAet,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> and,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> or,
                        List<String> to) {

        public Route {
            from = from == null ? List.of() : List.copyOf(from);
            and = and == null ? Map.of() : Map.copyOf(and);
            or = or == null ? Map.of() : Map.copyOf(or);
            to = to == null ? List.of() : List.copyOf(to);
            calledAet = calledAet == null || calledAet.isBlank() ? null : calledAet.strip();
        }
    }

    /**
     * Configuration DICOM d'une box : ses nœuds et ses routes. Remplace, quand elle est présente, les émetteurs
     * autorisés, les destinations DICOM et le PACS de restitution de la configuration locale.
     *
     * @param aet      AET de la box (appelé par les sources, et sous lequel elle restitue) ; {@code null} : local
     * @param nodes    nœuds du site
     * @param outgoing routes sortantes : d'un nœud source vers des box
     * @param incoming routes entrantes : d'une box émettrice vers des nœuds destinations
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Dicom(@JsonInclude(JsonInclude.Include.NON_NULL) String aet, List<Node> nodes, List<Route> outgoing,
                        List<Route> incoming) {

        public Dicom {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
            incoming = incoming == null ? List.of() : List.copyOf(incoming);
            aet = aet == null || aet.isBlank() ? null : aet.strip();
        }

        /** Nœud de ce nom, s'il existe. */
        public java.util.Optional<Node> node(String name) {
            return nodes.stream().filter(n -> n.name().equals(name)).findFirst();
        }
    }

    public static final String TYPE = "imagunity.configuration";
    /** Tolérance d'horloge pour un document émis « dans le futur ». */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(10);
    /**
     * Plus haut numéro de version admis : un numéro démesuré, signé par erreur ou glissé dans un brouillon par une
     * interface compromise, bloquerait toutes les versions suivantes (aucune box n'accepte un numéro inférieur).
     */
    public static final long MAX_SEQUENCE = 1_000_000_000L;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /**
     * Box visées : toute l'organisation, ou des box et des groupes de box. L'appartenance d'une box à un groupe est
     * fixée par le responsable dans son entrée d'annuaire signée ; une box ajoutée plus tard à un groupe reçoit la
     * configuration de ce groupe.
     *
     * @param all    toutes les box de l'organisation
     * @param boxes  box visées (si {@code all} est faux)
     * @param groups groupes visés (si {@code all} est faux)
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Target(boolean all, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> boxes,
                         @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> groups) {

        public Target {
            boxes = boxes == null ? List.of() : List.copyOf(boxes);
            try {
                groups = groups == null ? List.of() : BoxEntry.groups(groups);
            } catch (IllegalArgumentException e) {
                throw new DirectoryException("Configuration : " + e.getMessage());
            }
            if (!all && boxes.isEmpty() && groups.isEmpty()) {
                throw new DirectoryException("Configuration : aucune box visée (« all », des box ou des groupes)");
            }
            if (all && (!boxes.isEmpty() || !groups.isEmpty())) {
                throw new DirectoryException("Configuration : « all » et une liste de box ou de groupes à la fois");
            }
            if (Set.copyOf(boxes).size() != boxes.size()) {
                throw new DirectoryException("Configuration : box en double dans la liste");
            }
        }

        public Target(boolean all, List<UUID> boxes) {
            this(all, boxes, List.of());
        }

        public static Target everyone() {
            return new Target(true, List.of(), List.of());
        }

        public static Target of(List<UUID> boxes) {
            return new Target(false, boxes, List.of());
        }

        public static Target groups(List<String> groups) {
            return new Target(false, List.of(), groups);
        }

        /** Vrai si la box, membre de ces groupes (d'après son entrée d'annuaire), est visée. */
        public boolean includes(UUID boxId, java.util.Collection<String> boxGroups) {
            return all || boxes.contains(boxId) || groups.stream().anyMatch(boxGroups::contains);
        }

        public boolean includes(UUID boxId) {
            return includes(boxId, List.of());
        }

        /** Résumé lisible : « toute l'organisation », « 2 box, groupes imagerie-nord ». */
        public String describe() {
            if (all) {
                return "toute l'organisation";
            }
            List<String> parts = new java.util.ArrayList<>();
            if (!boxes.isEmpty()) {
                parts.add(boxes.size() + " box (" + boxes + ")");
            }
            if (!groups.isEmpty()) {
                parts.add("groupes " + String.join(", ", groups));
            }
            return String.join(", ", parts);
        }
    }

    /**
     * Règle de priorité ({@code priority}) ou de routage ({@code to}) : mêmes conditions DICOM que la configuration
     * locale ({@code and} : tous les champs, {@code or} : un seul suffit). Les mots-clés et les motifs sont vérifiés
     * par la box, qui refuse le document entier si une règle est invalide.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(String name,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer priority,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> and,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> or,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> to) {

        public Rule {
            and = and == null ? Map.of() : Map.copyOf(and);
            or = or == null ? Map.of() : Map.copyOf(or);
            to = to == null ? List.of() : List.copyOf(to);
        }
    }

    /** Règles de priorité : priorité par défaut (1 à 9) et règles, lues dans l'ordre. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Priority(@JsonProperty("default") Integer defaultPriority, List<Rule> rules) {

        public Priority {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    /** Règles de routage, lues dans l'ordre : la première qui correspond désigne les partenaires. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Routing(List<Rule> rules) {

        public Routing {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public CentralConfiguration {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    /** Nouveau document à signer. */
    public static CentralConfiguration draft(String organization, Target target, long sequence, Instant issuedAt,
                                             String author, String comment, Priority priority, Routing routing) {
        return draft(organization, target, sequence, issuedAt, author, comment, priority, routing, null);
    }

    /** Nouveau document à signer, avec un bloc DICOM. */
    public static CentralConfiguration draft(String organization, Target target, long sequence, Instant issuedAt,
                                             String author, String comment, Priority priority, Routing routing,
                                             Dicom dicom) {
        CentralConfiguration draft = new CentralConfiguration(TYPE, Suite.ID, organization, target, sequence,
                issuedAt, author, comment, priority, routing, dicom);
        draft.checkShape();
        return draft;
    }

    /** Vérifications de forme (sans les conditions DICOM, vérifiées par la box). */
    void checkShape() {
        if (!TYPE.equals(type)) {
            throw new DirectoryException("Document de type « " + type + " », configuration attendue");
        }
        if (!Suite.ID.equals(suite)) {
            throw new DirectoryException("Configuration : suite non prise en charge " + suite);
        }
        if (sequence < 1 || sequence > MAX_SEQUENCE) {
            throw new DirectoryException("Configuration : numéro de version invalide " + sequence);
        }
        if (priority == null && routing == null && dicom == null) {
            throw new DirectoryException("Configuration vide : ni priorité, ni routage, ni configuration DICOM");
        }
        if (dicom != null) {
            checkDicom(dicom);
        }
        if (priority != null) {
            if (priority.defaultPriority() != null && (priority.defaultPriority() < 1 || priority.defaultPriority() > 9)) {
                throw new DirectoryException("Configuration : priorité par défaut hors de 1 à 9");
            }
            checkRules(priority.rules(), "priorité", true);
        }
        if (routing != null) {
            checkRules(routing.rules(), "routage", false);
        }
    }

    /** AET : 1 à 16 caractères sûrs (ils servent aussi de noms de dossiers dans la box). */
    private static final Pattern AET = Pattern.compile("[A-Za-z0-9_\\-]{1,16}");
    /** Nœuds et routes au plus, par document. */
    static final int MAX_DICOM_ITEMS = 64;

    private static void requireAet(String aet, String what) {
        if (aet == null || !AET.matcher(aet).matches()) {
            throw new DirectoryException(what + " : AET invalide « " + aet + " » (1 à 16 caractères parmi A-Z, a-z, "
                    + "0-9, _ et -)");
        }
    }

    private static void checkDicom(Dicom dicom) {
        if (dicom.aet() != null) {
            requireAet(dicom.aet(), "AET de la box");
        }
        if (dicom.nodes().size() > MAX_DICOM_ITEMS || dicom.outgoing().size() > MAX_DICOM_ITEMS
                || dicom.incoming().size() > MAX_DICOM_ITEMS) {
            throw new DirectoryException("Configuration DICOM : " + MAX_DICOM_ITEMS + " nœuds ou routes au plus");
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> sourceAets = new java.util.HashSet<>();
        for (Node node : dicom.nodes()) {
            if (node.name() == null || !NAME.matcher(node.name()).matches()) {
                throw new DirectoryException("Nœud DICOM : nom invalide « " + node.name() + " »");
            }
            if (!names.add(node.name())) {
                throw new DirectoryException("Nœud DICOM en double : " + node.name());
            }
            requireAet(node.aet(), "Nœud " + node.name());
            if (!node.source() && !node.destination()) {
                throw new DirectoryException("Nœud " + node.name() + " : ni source ni destination");
            }
            if (node.host() != null && (node.host().isBlank() || node.host().length() > 253
                    || !node.host().matches("[A-Za-z0-9.:\\-]+"))) {
                throw new DirectoryException("Nœud " + node.name() + " : adresse invalide « " + node.host() + " »");
            }
            if (node.destination() && (node.host() == null || node.port() == null)) {
                throw new DirectoryException("Nœud " + node.name() + " : une destination a une adresse et un port");
            }
            if (node.port() != null && (node.port() < 1 || node.port() > 65535)) {
                throw new DirectoryException("Nœud " + node.name() + " : port hors de 1 à 65535");
            }
            if (node.source() && !sourceAets.add(node.aet())) {
                throw new DirectoryException("Deux nœuds sources ont l'AET " + node.aet());
            }
        }
        checkRoutes(dicom, dicom.outgoing(), true);
        checkRoutes(dicom, dicom.incoming(), false);
    }

    private static void checkRoutes(Dicom dicom, List<Route> routes, boolean outgoing) {
        String what = outgoing ? "Route sortante" : "Route entrante";
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Route route : routes) {
            if (route.name() == null || !NAME.matcher(route.name()).matches()) {
                throw new DirectoryException(what + " : nom invalide « " + route.name() + " »");
            }
            if (!names.add(route.name())) {
                throw new DirectoryException(what + " en double : " + route.name());
            }
            if (route.to().isEmpty()) {
                throw new DirectoryException(what + " " + route.name() + " : aucune destination");
            }
            if (outgoing) {
                for (String from : route.from()) {
                    if (dicom.node(from).filter(Node::source).isEmpty()) {
                        throw new DirectoryException(what + " " + route.name() + " : « " + from
                                + " » n'est pas un nœud source");
                    }
                }
                if (route.calledAet() != null) {
                    requireAet(route.calledAet(), what + " " + route.name());
                }
                route.to().forEach(box -> requireBox(box, what + " " + route.name()));
            } else {
                route.from().forEach(box -> requireBox(box, what + " " + route.name()));
                if (route.calledAet() != null) {
                    throw new DirectoryException(what + " " + route.name() + " : « calledAet » réservé aux routes "
                            + "sortantes");
                }
                for (String to : route.to()) {
                    if (dicom.node(to).filter(Node::destination).isEmpty()) {
                        throw new DirectoryException(what + " " + route.name() + " : « " + to
                                + " » n'est pas un nœud destination");
                    }
                }
            }
        }
    }

    private static void requireBox(String box, String what) {
        try {
            UUID.fromString(box);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DirectoryException(what + " : identifiant de box invalide « " + box + " »");
        }
    }

    private static void checkRules(List<Rule> rules, String what, boolean priority) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Rule rule : rules) {
            if (rule.name() == null || !NAME.matcher(rule.name()).matches()) {
                throw new DirectoryException("Règle de " + what + " : nom invalide « " + rule.name() + " »");
            }
            if (!names.add(rule.name())) {
                throw new DirectoryException("Règle de " + what + " en double : " + rule.name());
            }
            if (priority) {
                if (rule.priority() == null || rule.priority() < 1 || rule.priority() > 9) {
                    throw new DirectoryException("Règle de priorité " + rule.name() + " : priorité hors de 1 à 9");
                }
                if (!rule.to().isEmpty()) {
                    throw new DirectoryException("Règle de priorité " + rule.name() + " : « to » réservé au routage");
                }
            } else {
                if (rule.to().isEmpty()) {
                    throw new DirectoryException("Règle de routage " + rule.name() + " : aucun partenaire (« to »)");
                }
                if (rule.priority() != null) {
                    throw new DirectoryException("Règle de routage " + rule.name()
                            + " : « priority » réservé aux règles de priorité");
                }
            }
        }
    }

    public String toJson() {
        return Json.write(this);
    }

    /** Lit un document non signé (brouillon), avec les vérifications de forme. */
    public static CentralConfiguration fromJson(String json) {
        CentralConfiguration configuration = Json.read(json, CentralConfiguration.class, "Configuration");
        configuration.checkShape();
        return configuration;
    }

    /** Signe ce document avec la clé du responsable. */
    public SignedDocument sign(HybridKeyPair organizationKeys) {
        checkShape();
        return SignedDocument.sign(toJson(), organizationKeys);
    }

    /**
     * Vérifie un document publié : signature du responsable épinglé, organisation, forme, date d'émission.
     *
     * @throws DirectoryException si le document est refusé, avec la raison
     */
    public static CentralConfiguration verify(SignedDocument document, String organization,
                                              HybridPublicKey organizationKey, Clock clock) {
        if (!document.isSignedBy(organizationKey)) {
            throw new DirectoryException("Configuration non signée par le responsable de l'organisation");
        }
        CentralConfiguration configuration = fromJson(document.payload());
        if (!organization.equals(configuration.organization())) {
            throw new DirectoryException("Configuration d'une autre organisation : " + configuration.organization());
        }
        if (configuration.issuedAt().isAfter(clock.instant().plus(CLOCK_SKEW))) {
            throw new DirectoryException("Configuration émise dans le futur (" + configuration.issuedAt() + ")");
        }
        return configuration;
    }

    /** Résumé lisible, pour les journaux et la console (noms de règles seulement). */
    public String describe() {
        StringBuilder text = new StringBuilder("n° " + sequence + " du " + issuedAt);
        if (priority != null) {
            text.append(", priorité : ").append(priority.rules().size()).append(" règle(s)");
        }
        if (routing != null) {
            text.append(", routage : ").append(routing.rules().size()).append(" règle(s)");
        }
        if (dicom != null) {
            text.append(", DICOM : ").append(dicom.nodes().size()).append(" nœud(s), ")
                    .append(dicom.outgoing().size()).append(" route(s) sortante(s), ").append(dicom.incoming().size())
                    .append(" route(s) entrante(s)");
        }
        return text.toString();
    }
}
