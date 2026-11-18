package com.imagunity.configuration;

import com.imagunity.rules.DicomRouting;
import com.imagunity.rules.PrioritySource;
import com.imagunity.rules.RoutingRule;
import com.imagunity.rules.RuleSet;
import com.imagunity.directory.CentralConfiguration;
import com.imagunity.directory.ConfigurationReport;
import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.NodeCheck;
import com.imagunity.directory.SignedDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configuration centralisée appliquée par cette box (docs/reference/configuration-centralisee.md).
 *
 * <ul>
 *   <li>À chaque lecture de l'annuaire, les documents publiés sont vérifiés avec la clé épinglée du responsable ; la
 *       box retient le document de numéro le plus haut qui la vise, s'il est plus récent que celui qu'elle applique.</li>
 *   <li>Ses règles sont compilées comme la configuration locale ; un document dont une règle est invalide est refusé,
 *       les règles précédentes restent en vigueur, et le refus est visible dans la console et remonté à l'annuaire.</li>
 *   <li>Un document plus ancien que celui appliqué n'est jamais appliqué (pas de retour en arrière imposé par un
 *       serveur compromis) ; le document appliqué est gardé sur disque et revérifié au démarrage.</li>
 *   <li>Les règles centrales l'emportent sur les règles locales ; un bloc absent du document (priorité ou routage)
 *       laisse les règles locales en vigueur.</li>
 * </ul>
 */
public final class CentralRules implements CentralConfigurationStatus {

    private static final Logger LOG = LoggerFactory.getLogger(CentralRules.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Compte rendu renvoyé au moins à cet intervalle, même sans changement. */
    static final Duration REPORT_EVERY = Duration.ofHours(1);

    /**
     * État gardé sur disque.
     *
     * @param document  document appliqué, ou {@code null} (configuration locale)
     * @param appliedAt date d'application
     * @param floor     plus haute version déjà appliquée : aucun document de numéro inférieur ou égal ne sera appliqué
     * @param seen      documents vérifiés déjà vus (les {@value #REMEMBERED} plus récents), pour le repli
     */
    record Stored(String document, Instant appliedAt, Long floor, List<String> seen) {

        Stored(String document, Instant appliedAt, Long floor) {
            this(document, appliedAt, floor, null);
        }
    }

    /**
     * Documents vérifiés gardés en mémoire : quand la box n'est plus visée par le document appliqué, elle se replie
     * sur le plus récent de ceux qu'elle a déjà vus et qui la visent encore, et un service d'annuaire compromis ne
     * peut pas lui en faire appliquer un plus ancien en cachant les autres.
     */
    static final int REMEMBERED = 32;

    /** Version appliquée. */
    record Applied(CentralConfiguration configuration, RuleSet rules, DicomRouting dicom, Instant appliedAt) {
    }

    /** Règles et configuration DICOM compilées d'un document. */
    record Compiled(RuleSet rules, DicomRouting dicom) {
    }

    /**
     * État pour la console et le compte rendu.
     *
     * @param sequence  version appliquée, ou 0 (configuration locale)
     * @param issuedAt  date d'émission de la version appliquée
     * @param author    auteur déclaré
     * @param comment   motif déclaré
     * @param appliedAt date d'application
     * @param priority  vrai si la version fixe les règles de priorité
     * @param routing   vrai si elle fixe les règles de routage
     * @param refused   dernière version refusée, ou 0
     * @param refusal   raison du refus
     * @param checkedAt dernière lecture des documents publiés
     * @param error     dernière erreur de lecture (annuaire injoignable…)
     */
    public record Snapshot(long sequence, Instant issuedAt, String author, String comment, Instant appliedAt,
                           boolean priority, boolean routing, long refused, String refusal, Instant checkedAt,
                           String error) {
    }

    private final UUID boxId;
    private final String organization;
    private final HybridPublicKey organizationKey;
    private final Path file;
    private final Clock clock;
    private volatile Applied applied;
    /** Document appliqué (JSON signé), ou {@code null}. */
    private String appliedDocument;
    /**
     * Document enregistré qui ne s'est pas revérifié au démarrage (horloge en retard, règle devenue invalide après une
     * mise à jour du logiciel…) : gardé sur le disque, et son numéro peut de nouveau s'appliquer à la lecture suivante
     * de l'annuaire. Sans cela, le plancher anti-retour (son numéro) l'écarterait à jamais, au profit des règles
     * locales, jusqu'au document suivant du responsable.
     */
    private String unverifiedDocument;
    private Instant storedAt;
    /** Documents vérifiés déjà vus, par numéro. */
    private final java.util.TreeMap<Long, Candidate> seen = new java.util.TreeMap<>();
    private volatile long refused;
    private volatile String refusal;
    private volatile Instant checkedAt;
    private volatile String error;
    private Instant reportedAt;
    private String reportedState;
    private volatile Supplier<List<NodeCheck>> nodeChecks = List::of;
    /** Dernière demande d'essai traitée, reprise dans le compte rendu. */
    private volatile String answered;

    /** Groupes de cette box, d'après son entrée d'annuaire vérifiée. */
    private final java.util.function.Supplier<java.util.Collection<String>> groups;
    /** Plus haute version déjà appliquée. */
    private volatile long floor;

    public CentralRules(UUID boxId, String organization, HybridPublicKey organizationKey, Path file, Clock clock) {
        this(boxId, organization, organizationKey, file, clock, List::of);
    }

    /**
     * @param groups groupes de cette box, lus dans son entrée d'annuaire à chaque examen des documents
     */
    public CentralRules(UUID boxId, String organization, HybridPublicKey organizationKey, Path file, Clock clock,
                        java.util.function.Supplier<java.util.Collection<String>> groups) {
        this.groups = groups;
        this.boxId = boxId;
        this.organization = organization;
        this.organizationKey = organizationKey;
        this.file = file;
        this.clock = clock;
    }

    /** Relit le document appliqué avant l'arrêt, revérifié ; ignoré (avec avertissement) s'il ne l'est plus. */
    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Stored stored = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
            floor = stored.floor() == null ? 0 : stored.floor();
            storedAt = stored.appliedAt();
            for (String json : stored.seen() == null ? List.<String>of() : stored.seen()) {
                try {
                    verified(SignedDocument.fromJson(json)).ifPresent(this::remember);
                } catch (RuntimeException e) {
                    LOG.warn("Document de configuration gardé illisible : écarté ({})", e.getMessage());
                }
            }
            if (stored.document() == null) {
                return;
            }
            CentralConfiguration configuration;
            Compiled rules;
            try {
                configuration = CentralConfiguration.verify(SignedDocument.fromJson(stored.document()), organization,
                        organizationKey, clock);
                rules = compileAll(configuration);
            } catch (RuntimeException e) {
                unverifiedDocument = stored.document();
                LOG.warn("Configuration centrale enregistrée invalide ({}) : configuration locale en vigueur jusqu'à "
                        + "la prochaine lecture de l'annuaire", e.getMessage());
                return;
            }
            applied = new Applied(configuration, rules.rules(), rules.dicom(), stored.appliedAt());
            appliedDocument = stored.document();
            floor = Math.max(floor, configuration.sequence());
            LOG.info("Configuration centrale {} en vigueur (appliquée le {})", configuration.describe(),
                    stored.appliedAt());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Configuration centrale enregistrée illisible ou invalide ({}) : configuration locale en vigueur "
                    + "jusqu'à la prochaine lecture de l'annuaire", e.getMessage());
        }
    }

    /**
     * Examine les documents publiés et applique le plus récent qui vise cette box.
     *
     * @return vrai si une nouvelle version a été appliquée
     */
    public synchronized boolean synchronize(List<SignedDocument> documents) {
        checkedAt = clock.instant();
        error = null;
        java.util.Collection<String> boxGroups = groups.get();
        Applied current0 = applied;
        boolean dropped = false;
        if (current0 != null && !current0.configuration().target().includes(boxId, boxGroups)) {
            // Box retirée du groupe visé (entrée réémise et signée par le responsable) : le document le plus récent
            // qui la vise encore s'applique, sinon la configuration locale.
            LOG.info("Configuration centrale n° {} : cette box n'est plus visée (groupes {})",
                    current0.configuration().sequence(), boxGroups);
            applied = null;
            appliedDocument = null;
            dropped = true;
        }
        // Sinon, seuls les documents plus récents que tout ce qui a déjà été appliqué comptent (pas de retour en
        // arrière, même après un redémarrage).
        // Document enregistré non revérifié au démarrage : son propre numéro reste admis (pas un plus ancien).
        long current = dropped ? 0 : unverifiedDocument != null ? floor - 1 : floor;
        List<Candidate> published = documents.stream().map(this::verified).flatMap(Optional::stream).toList();
        boolean learned = false;
        for (Candidate candidate : published) {
            learned |= remember(candidate);
        }
        // Au repli, les documents déjà vus comptent aussi : l'annuaire ne choisit pas en les cachant.
        java.util.Map<Long, Candidate> pool = new java.util.HashMap<>();
        (dropped ? seen.values() : published).forEach(c -> pool.putIfAbsent(c.configuration().sequence(), c));
        published.forEach(c -> pool.putIfAbsent(c.configuration().sequence(), c));
        List<Candidate> candidates = pool.values().stream()
                .filter(c -> c.configuration().target().includes(boxId, boxGroups))
                .filter(c -> c.configuration().sequence() > current)
                .sorted(Comparator.comparingLong((Candidate c) -> c.configuration().sequence()).reversed()).toList();
        for (Candidate found : candidates) {
            CentralConfiguration candidate = found.configuration();
            Compiled rules;
            try {
                rules = compileAll(candidate);
            } catch (IllegalArgumentException e) {
                if (candidate.sequence() > refused) {
                    refused = candidate.sequence();
                    refusal = e.getMessage();
                    LOG.warn("Configuration centrale n° {} refusée, la configuration en vigueur reste appliquée : {}",
                            candidate.sequence(), e.getMessage());
                }
                continue;
            }
            Instant now = clock.instant();
            applied = new Applied(candidate, rules.rules(), rules.dicom(), now);
            appliedDocument = found.document().toJson();
            unverifiedDocument = null;
            floor = Math.max(floor, candidate.sequence());
            save(now);
            LOG.info("Configuration centrale {} appliquée{}", candidate.describe(), candidate.author() == null ? ""
                    : " (par " + candidate.author() + (candidate.comment() == null ? "" : " : " + candidate.comment())
                    + ")");
            return true;
        }
        if (dropped) {
            LOG.info("Aucune autre configuration centrale ne vise cette box : configuration locale en vigueur");
            save(clock.instant());
            return true;
        }
        if (learned) {
            save(storedAt);
        }
        return false;
    }

    /** Erreur de lecture des documents (annuaire injoignable…) : la configuration en vigueur reste appliquée. */
    public synchronized void failed(String reason) {
        checkedAt = clock.instant();
        error = reason;
    }

    private record Candidate(SignedDocument document, CentralConfiguration configuration) {
    }

    private Optional<Candidate> verified(SignedDocument document) {
        try {
            return Optional.of(new Candidate(document, CentralConfiguration.verify(document, organization,
                    organizationKey, clock)));
        } catch (DirectoryException e) {
            LOG.warn("Document de configuration écarté : {}", e.getMessage());
            return Optional.empty();
        }
    }

    static RuleSet compile(CentralConfiguration configuration) {
        return RuleSet.compile(configuration.priority() == null ? null : configuration.priority().defaultPriority(),
                configuration.priority() == null ? null : specs(configuration.priority().rules()),
                configuration.routing() == null ? null : specs(configuration.routing().rules()));
    }

    /** Règles et configuration DICOM d'un document ; {@code IllegalArgumentException} au premier élément invalide. */
    static Compiled compileAll(CentralConfiguration configuration) {
        return new Compiled(compile(configuration), dicom(configuration.dicom()));
    }

    /** Configuration DICOM compilée, ou {@code null} si le document n'en porte pas. */
    public static DicomRouting dicom(CentralConfiguration.Dicom dicom) {
        if (dicom == null) {
            return null;
        }
        return DicomRouting.compile(dicom.aet(), dicom.nodes().stream().map(n -> new DicomRouting.NodeSpec(n.name(),
                        n.aet(), n.host(), n.port(), n.source(), n.destination())).toList(),
                routes(dicom.outgoing()), routes(dicom.incoming()));
    }

    private static List<DicomRouting.RouteSpec> routes(List<CentralConfiguration.Route> routes) {
        return routes.stream().map(r -> new DicomRouting.RouteSpec(r.name(), r.from(), r.calledAet(), r.and(), r.or(),
                r.to())).toList();
    }

    private static List<RuleSet.Spec> specs(List<CentralConfiguration.Rule> rules) {
        return rules.stream().map(r -> new RuleSet.Spec(r.name(), r.priority(), r.and(), r.or(), r.to())).toList();
    }

    /** Retient un document vérifié ; @return vrai s'il est nouveau et gardé */
    private boolean remember(Candidate candidate) {
        long sequence = candidate.configuration().sequence();
        if (seen.containsKey(sequence) || seen.size() >= REMEMBERED && sequence < seen.firstKey()) {
            return false;
        }
        seen.put(sequence, candidate);
        while (seen.size() > REMEMBERED) {
            seen.pollFirstEntry();
        }
        return true;
    }

    private void save(Instant at) {
        storedAt = at;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, JSON.writeValueAsString(new Stored(
                    appliedDocument != null ? appliedDocument : unverifiedDocument, at, floor,
                    seen.values().stream().map(c -> c.document().toJson()).toList())), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Configuration centrale non enregistrée ({}) : elle sera relue à la prochaine lecture de "
                    + "l'annuaire après un redémarrage", e.getMessage());
        }
    }

    /** Règles de priorité centrales, si la version appliquée en fixe. */
    public Optional<PrioritySource.Snapshot> priority() {
        Applied a = applied;
        if (a == null || a.rules().priority() == null) {
            return Optional.empty();
        }
        return Optional.of(new PrioritySource.Snapshot(a.rules().priority(), "configuration centrale n° "
                + a.configuration().sequence(), a.appliedAt(), refusal));
    }

    /** Règles de routage centrales, si la version appliquée en fixe. */
    public Optional<List<RoutingRule>> routing() {
        Applied a = applied;
        return a == null ? Optional.empty() : Optional.ofNullable(a.rules().routing());
    }

    /** Configuration DICOM centrale, si la version appliquée en fixe une. */
    public Optional<DicomRouting> dicom() {
        Applied a = applied;
        return a == null ? Optional.empty() : Optional.ofNullable(a.dicom());
    }

    /** Lignes lisibles de la configuration DICOM centrale (console). */
    static List<String> describe(DicomRouting d) {
        return describe(d, java.util.Map.of());
    }

    private static final java.time.format.DateTimeFormatter SEEN = java.time.format.DateTimeFormatter
            .ofPattern("dd/MM HH:mm").withZone(java.time.ZoneId.systemDefault());

    /** Ce que la box a constaté d'un nœud : essai de connexion, envoi reçu, connexion refusée. */
    static String seen(NodeCheck c) {
        if (c == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (c.echoAt() != null) {
            text.append(c.echoError() == null ? " — C-ECHO réussi le " + SEEN.format(c.echoAt())
                    : " — C-ECHO en échec le " + SEEN.format(c.echoAt()) + " : " + c.echoError());
        }
        if (c.receivedAt() != null) {
            text.append(" — envoi reçu le ").append(SEEN.format(c.receivedAt()));
        }
        if (c.refusedAt() != null) {
            text.append(" — connexion refusée depuis ").append(c.refusedFrom()).append(" le ")
                    .append(SEEN.format(c.refusedAt()));
        }
        return text.toString();
    }

    static List<String> describe(DicomRouting d, java.util.Map<String, NodeCheck> checks) {
        List<String> lines = new java.util.ArrayList<>();
        if (d.aet() != null) {
            lines.add("AET de la box : " + d.aet());
        }
        d.nodes().values().stream().sorted(Comparator.comparing(DicomRouting.Node::name)).forEach(n -> lines.add(
                "Nœud " + n.name() + " (" + n.aet() + (n.host() == null ? "" : ", " + n.host()
                        + (n.port() == null ? "" : ":" + n.port())) + ") : " + (n.source() && n.destination()
                        ? "source et destination" : n.source() ? "source" : "destination") + seen(checks.get(n.name()))));
        d.outgoing().forEach(r -> lines.add("Route sortante " + r.name() + " : " + (r.fromAets().isEmpty()
                ? "toute source" : String.join(", ", r.fromAets())) + (r.calledAet() == null ? ""
                : " vers " + r.calledAet()) + (r.condition().and().isEmpty() && r.condition().or().isEmpty() ? ""
                : " [" + r.condition().describe() + "]") + " → " + r.to().size() + " box"));
        d.incoming().forEach(r -> lines.add("Route entrante " + r.name() + " : " + (r.from().isEmpty()
                ? "toute box" : r.from().size() + " box") + (r.condition().and().isEmpty()
                && r.condition().or().isEmpty() ? "" : " [" + r.condition().describe() + "]") + " → "
                + String.join(", ", r.to())));
        return lines;
    }

    public Snapshot snapshot() {
        Applied a = applied;
        CentralConfiguration c = a == null ? null : a.configuration();
        return new Snapshot(c == null ? 0 : c.sequence(), c == null ? null : c.issuedAt(),
                c == null ? null : c.author(), c == null ? null : c.comment(), a == null ? null : a.appliedAt(),
                a != null && a.rules().priority() != null, a != null && a.rules().routing() != null, refused, refusal,
                checkedAt, error);
    }

    @Override
    public View status() {
        Snapshot s = snapshot();
        List<String> routingRules = routing().map(rules -> rules.stream().map(r -> r.name() + " → "
                + String.join(", ", r.destinations())).toList()).orElse(List.of());
        return new View(true, s.sequence(), s.issuedAt(), s.author(), s.comment(), s.appliedAt(), s.priority(),
                s.routing(), routingRules, s.refused(), s.refusal(), s.checkedAt(), s.error(),
                List.copyOf(groups.get()), dicom().map(d -> describe(d, nodeChecks.get().stream().collect(
                        java.util.stream.Collectors.toMap(NodeCheck::node, c -> c, (a, b) -> a)))).orElse(List.of()));
    }

    /**
     * Lit les documents de configuration publiés, applique le plus récent qui vise la box, puis rend compte à
     * l'annuaire si c'est dû. Une erreur de lecture est gardée pour la console ; les règles en vigueur restent.
     */
    public void synchronizeWith(DirectoryClient client, HybridKeyPair boxKeys) {
        synchronizeWith(client, boxKeys, () -> { });
    }

    /**
     * Comme {@link #synchronizeWith(DirectoryClient, HybridKeyPair)}, avec une étape entre l'application et le compte
     * rendu (essais de connexion des appareils d'une configuration tout juste appliquée).
     */
    public void synchronizeWith(DirectoryClient client, HybridKeyPair boxKeys, Runnable beforeReport) {
        try {
            synchronize(client.fetchConfigurations());
        } catch (DirectoryException e) {
            failed(e.getMessage());
            LOG.debug("Configuration centrale non lue : {}", e.getMessage());
            return;
        }
        try {
            beforeReport.run();
        } catch (RuntimeException e) {
            LOG.warn("Essais des appareils DICOM en échec : {}", e.getMessage());
        }
        reportIfDue(boxKeys).ifPresent(report -> {
            try {
                client.report(report);
                reported(report);
            } catch (DirectoryException e) {
                LOG.debug("Compte rendu de configuration non remis : {}", e.getMessage());
            }
        });
    }

    /**
     * Compte rendu à envoyer à l'annuaire : à chaque changement d'état, et au moins une fois par heure ; vide sinon.
     */
    public synchronized Optional<SignedDocument> reportIfDue(HybridKeyPair boxKeys) {
        String state = state();
        Instant now = clock.instant();
        if (state.equals(reportedState) && reportedAt != null && now.isBefore(reportedAt.plus(REPORT_EVERY))) {
            return Optional.empty();
        }
        Snapshot s = snapshot();
        return Optional.of(ConfigurationReport.of(boxId, s.sequence(), s.appliedAt(), s.refused(), s.refusal(), now)
                .withNodes(nodeChecks.get()).withAnswered(answered).sign(boxKeys));
    }

    /** Une demande d'essai vient d'être traitée : le compte rendu suivant part tout de suite, et la nomme. */
    public void answered(String requestId) {
        this.answered = requestId;
    }

    /** État des appareils DICOM du site, joint au compte rendu (essais de connexion, envois reçus). */
    public void nodeChecks(Supplier<List<NodeCheck>> checks) {
        this.nodeChecks = checks;
    }

    private String state() {
        Snapshot s = snapshot();
        StringBuilder state = new StringBuilder(s.sequence() + "/" + s.refused() + "/" + answered);
        nodeChecks.get().forEach(c -> state.append('|').append(c.state()));
        return state.toString();
    }

    /** Le compte rendu a été reçu par l'annuaire. */
    public synchronized void reported(SignedDocument report) {
        reportedAt = clock.instant();
        reportedState = state();
    }
}
