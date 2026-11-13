package com.imagunity.config;

import com.imagunity.dicom.PrioritySource;
import com.imagunity.dicom.RoutingRule;
import com.imagunity.dicom.RuleSet;
import com.imagunity.directory.CentralConfiguration;
import com.imagunity.directory.ConfigurationReport;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridPublicKey;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configuration centralisée appliquée par cette box (docs/configuration-centralisee.md).
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
public final class CentralRules implements com.imagunity.api.CentralConfigurationController.Status {

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
    record Applied(CentralConfiguration configuration, RuleSet rules, Instant appliedAt) {
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
    private Instant storedAt;
    /** Documents vérifiés déjà vus, par numéro. */
    private final java.util.TreeMap<Long, Candidate> seen = new java.util.TreeMap<>();
    private volatile long refused;
    private volatile String refusal;
    private volatile Instant checkedAt;
    private volatile String error;
    private Instant reportedAt;
    private String reportedState;

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
            SignedDocument document = SignedDocument.fromJson(stored.document());
            CentralConfiguration configuration = CentralConfiguration.verify(document, organization, organizationKey,
                    clock);
            applied = new Applied(configuration, compile(configuration), stored.appliedAt());
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
        long current = dropped ? 0 : floor;
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
            RuleSet rules;
            try {
                rules = compile(candidate);
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
            applied = new Applied(candidate, rules, now);
            appliedDocument = found.document().toJson();
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
            Files.writeString(temp, JSON.writeValueAsString(new Stored(appliedDocument, at, floor,
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
                List.copyOf(groups.get()));
    }

    /**
     * Compte rendu à envoyer à l'annuaire : à chaque changement d'état, et au moins une fois par heure ; vide sinon.
     */
    public synchronized Optional<SignedDocument> reportIfDue(HybridKeyPair boxKeys) {
        Snapshot s = snapshot();
        String state = s.sequence() + "/" + s.refused();
        Instant now = clock.instant();
        if (state.equals(reportedState) && reportedAt != null && now.isBefore(reportedAt.plus(REPORT_EVERY))) {
            return Optional.empty();
        }
        return Optional.of(ConfigurationReport.of(boxId, s.sequence(), s.appliedAt(), s.refused(), s.refusal(), now)
                .sign(boxKeys));
    }

    /** Le compte rendu a été reçu par l'annuaire. */
    public synchronized void reported(SignedDocument report) {
        reportedAt = clock.instant();
        Snapshot s = snapshot();
        reportedState = s.sequence() + "/" + s.refused();
    }
}
