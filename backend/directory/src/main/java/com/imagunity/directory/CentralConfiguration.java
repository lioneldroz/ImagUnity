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
 * Document de configuration centralisée (docs/configuration-centralisee.md) : règles de priorité et de routage des
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
 */
public record CentralConfiguration(String type, String suite, String organization, Target target, long sequence,
                                   Instant issuedAt,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String author,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String comment,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Priority priority,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Routing routing) {

    public static final String TYPE = "imagunity.configuration";
    /** Tolérance d'horloge pour un document émis « dans le futur ». */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(10);
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
    public record Priority(@JsonProperty("default") Integer defaultPriority, List<Rule> rules) {

        public Priority {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    /** Règles de routage, lues dans l'ordre : la première qui correspond désigne les partenaires. */
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
        CentralConfiguration draft = new CentralConfiguration(TYPE, Suite.ID, organization, target, sequence,
                issuedAt, author, comment, priority, routing);
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
        if (sequence < 1) {
            throw new DirectoryException("Configuration : numéro de version invalide " + sequence);
        }
        if (priority == null && routing == null) {
            throw new DirectoryException("Configuration vide : ni priorité ni routage");
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
        return text.toString();
    }
}
