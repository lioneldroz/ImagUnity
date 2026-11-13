package com.imagunity.directoryserver;

import com.imagunity.dicom.RuleSet;
import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.CentralConfiguration;
import com.imagunity.directory.ConfigurationReport;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuration centralisée côté service d'annuaire : dépôt des documents signés par le responsable (revérifiés,
 * numéros croissants), comptes rendus des box (signés par elles), et aide à la préparation des documents pour
 * l'interface d'administration. Le service ne signe rien : la clé du responsable reste hors ligne.
 */
public class ConfigurationService {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationService.class);

    private final ConfigurationStore store;
    private final EntryStore entries;
    private final String organization;
    private final HybridPublicKey organizationKey;
    private final EntryVerifier verifier;
    private final Clock clock;

    public ConfigurationService(ConfigurationStore store, EntryStore entries, String organization,
                                HybridPublicKey organizationKey, Clock clock) {
        this.store = store;
        this.entries = entries;
        this.organization = organization;
        this.organizationKey = organizationKey;
        this.verifier = new EntryVerifier(organization, organizationKey, SerialMemory.inMemory(), clock);
        this.clock = clock;
    }

    /** Dépose un document signé ; 409 si son numéro n'est pas le plus haut, ou s'il est pris par un autre document. */
    @Transactional
    public PublicationService.Outcome publish(SignedDocument document) {
        CentralConfiguration configuration = verified(document);
        OptionalLong highest = store.highestSequence();
        if (highest.isPresent() && configuration.sequence() <= highest.getAsLong()) {
            if (store.find(configuration.sequence()).map(document::equals).orElse(false)) {
                return PublicationService.Outcome.UNCHANGED;
            }
            throw new PublicationService.ConflictException("Configuration n° " + configuration.sequence()
                    + " refusée : la dernière publiée est la n° " + highest.getAsLong()
                    + " (numéro de version croissant)");
        }
        store.save(configuration.sequence(), document, configuration.issuedAt(), clock.instant());
        LOG.info("Configuration publiée : {} ({}){}", configuration.describe(),
                configuration.target().all() ? "toute l'organisation" : configuration.target().boxes().size() + " box",
                configuration.author() == null ? "" : ", par " + configuration.author());
        return PublicationService.Outcome.PUBLISHED;
    }

    private CentralConfiguration verified(SignedDocument document) {
        CentralConfiguration configuration;
        try {
            configuration = CentralConfiguration.verify(document, organization, organizationKey, clock);
        } catch (DirectoryException e) {
            throw new PublicationService.InvalidEntryException(e.getMessage());
        }
        check(configuration);
        return configuration;
    }

    /** Vérifie les conditions DICOM et les partenaires, comme la box le fera. */
    static RuleSet check(CentralConfiguration configuration) {
        try {
            return rules(configuration);
        } catch (IllegalArgumentException e) {
            throw new PublicationService.InvalidEntryException("Règle invalide : " + e.getMessage());
        }
    }

    static RuleSet rules(CentralConfiguration configuration) {
        return RuleSet.compile(configuration.priority() == null ? null : configuration.priority().defaultPriority(),
                configuration.priority() == null ? null : specs(configuration.priority().rules()),
                configuration.routing() == null ? null : specs(configuration.routing().rules()));
    }

    static List<RuleSet.Spec> specs(List<CentralConfiguration.Rule> rules) {
        return rules.stream().map(r -> new RuleSet.Spec(r.name(), r.priority(), r.and(), r.or(), r.to())).toList();
    }

    public List<SignedDocument> documents() {
        return store.all();
    }

    /** Enregistre le compte rendu d'une box, signé par elle ; faux s'il est plus ancien que le dernier reçu. */
    @Transactional
    public boolean report(SignedDocument document) {
        ConfigurationReport report;
        try {
            report = ConfigurationReport.verify(document, this::signingKey);
        } catch (DirectoryException e) {
            throw new PublicationService.InvalidEntryException(e.getMessage());
        }
        if (report.reportedAt().isAfter(clock.instant().plus(java.time.Duration.ofMinutes(10)))) {
            throw new PublicationService.InvalidEntryException("Compte rendu daté du futur");
        }
        boolean saved = store.saveReport(report, clock.instant());
        if (saved && report.refusal() != null) {
            LOG.warn("Box {} : configuration n° {} refusée : {}", report.boxId(), report.refused(), report.refusal());
        }
        return saved;
    }

    private Optional<HybridPublicKey> signingKey(UUID boxId) {
        // Seule une box active rend compte : ni une box révoquée, ni une box dont l'entrée a expiré.
        return entries.current(boxId).flatMap(this::entry)
                .filter(e -> e.statusAt(clock.instant()) == BoxEntry.Status.ACTIVE)
                .map(e -> HybridPublicKey.decode(e.signingKeys()));
    }

    private Optional<BoxEntry> entry(SignedDocument document) {
        try {
            return Optional.of(verifier.verify(document).entry());
        } catch (DirectoryException e) {
            return Optional.empty();
        }
    }

    // --- Interface d'administration ---

    /** Box de l'annuaire, pour l'interface. */
    public record BoxView(UUID boxId, String displayName, String status, List<String> groups, Long applied,
                          java.time.Instant appliedAt, Long refused, String refusal, java.time.Instant reportedAt) {
    }

    /** Version publiée, décodée pour l'interface. */
    public record DocumentView(long sequence, java.time.Instant issuedAt, String author, String comment,
                               CentralConfiguration.Target target, CentralConfiguration.Priority priority,
                               CentralConfiguration.Routing routing) {
    }

    /**
     * État pour l'interface : box, groupes connus (ceux des box actives), documents publiés (du plus récent au plus
     * ancien), prochain numéro.
     */
    public record State(String organization, List<BoxView> boxes, List<String> groups, List<DocumentView> documents,
                        long nextSequence) {
    }

    public State state() {
        Map<UUID, ConfigurationStore.Report> reports = new java.util.HashMap<>();
        store.reports().forEach(r -> reports.put(r.boxId(), r));
        List<BoxView> boxes = new ArrayList<>();
        for (SignedDocument document : entries.current()) {
            entry(document).ifPresent(e -> {
                ConfigurationStore.Report r = reports.get(e.boxId());
                boxes.add(new BoxView(e.boxId(), e.displayName(), e.statusAt(clock.instant()).name(), e.groups(),
                        r == null ? null : r.applied(), r == null ? null : r.appliedAt(),
                        r == null ? null : r.refused(), r == null ? null : r.refusal(),
                        r == null ? null : r.reportedAt()));
            });
        }
        boxes.sort(java.util.Comparator.comparing(BoxView::displayName));
        List<DocumentView> documents = new ArrayList<>();
        for (SignedDocument document : store.all()) {
            CentralConfiguration c = CentralConfiguration.verify(document, organization, organizationKey,
                    Clock.offset(clock, java.time.Duration.ofDays(3650)));
            documents.addFirst(new DocumentView(c.sequence(), c.issuedAt(), c.author(), c.comment(), c.target(),
                    c.priority(), c.routing()));
        }
        List<String> groups = boxes.stream().filter(b -> "ACTIVE".equals(b.status()))
                .flatMap(b -> b.groups().stream()).distinct().sorted().toList();
        return new State(organization, boxes, groups, documents, store.highestSequence().orElse(0) + 1);
    }

    /** Brouillon demandé par l'interface. */
    public record DraftRequest(CentralConfiguration.Target target, String author, String comment,
                               CentralConfiguration.Priority priority, CentralConfiguration.Routing routing) {
    }

    /** Prépare le document à signer : prochain numéro, date d'émission, règles vérifiées comme par la box. */
    public CentralConfiguration draft(DraftRequest request) {
        CentralConfiguration draft;
        try {
            draft = CentralConfiguration.draft(organization, request.target(), store.highestSequence().orElse(0) + 1,
                    clock.instant(), blankToNull(request.author()), blankToNull(request.comment()), request.priority(),
                    request.routing());
        } catch (DirectoryException | NullPointerException e) {
            throw new PublicationService.InvalidEntryException(e.getMessage() == null ? "Brouillon incomplet"
                    : e.getMessage());
        }
        check(draft);
        return draft;
    }

    /** Essaie des règles (brouillon) sur un en-tête d'essai. */
    public RuleSet.Trial trial(DraftRequest request, Map<String, String> header) {
        CentralConfiguration draft = draft(request);
        try {
            return rules(draft).trial(RuleSet.dataset(header));
        } catch (IllegalArgumentException e) {
            throw new PublicationService.InvalidEntryException(e.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
