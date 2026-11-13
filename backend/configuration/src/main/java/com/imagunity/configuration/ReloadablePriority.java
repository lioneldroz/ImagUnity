package com.imagunity.configuration;

import com.imagunity.rules.DicomCondition;
import com.imagunity.rules.PriorityRule;
import com.imagunity.rules.PriorityRules;
import com.imagunity.rules.PrioritySource;
import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.SeriesPriority;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Règles de priorité en vigueur : celles des propriétés, ou celles d'un fichier JSON relu dès qu'il change (vérifié
 * avant chaque empaquetage). Un fichier devenu invalide est refusé : les règles précédentes restent en vigueur, et
 * l'erreur est visible dans la console.
 *
 * <pre>
 * { "default": 5,
 *   "rules": [ { "name": "scanner-thorax", "priority": 1,
 *                "and": { "Modality": "CT", "SeriesDescription": "*THORAX*|*POUMON*" } },
 *              { "name": "localisateurs-en-dernier", "priority": 9,
 *                "or": { "ImageType": "*LOCALIZER*", "SeriesDescription": "*SCOUT*|*TOPO*" } } ] }
 * </pre>
 */
public final class ReloadablePriority implements SeriesPriority, PrioritySource {

    private static final Logger LOG = LoggerFactory.getLogger(ReloadablePriority.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** Document JSON des règles (format de la configuration centralisée). */
    record Document(Integer defaultPriority, List<DocumentRule> rules) {

        @com.fasterxml.jackson.annotation.JsonCreator
        Document(@com.fasterxml.jackson.annotation.JsonProperty("default") Integer defaultPriority,
                 @com.fasterxml.jackson.annotation.JsonProperty("rules") List<DocumentRule> rules) {
            this.defaultPriority = defaultPriority;
            this.rules = rules;
        }
    }

    record DocumentRule(String name, int priority, Map<String, String> and, Map<String, String> or) {
    }

    private final Path file;
    private final Clock clock;
    private volatile Snapshot state;
    /** Règles de la configuration centralisée, si elle en fixe : elles l'emportent sur les règles locales. */
    private volatile java.util.function.Supplier<java.util.Optional<Snapshot>> central = java.util.Optional::empty;
    private FileTime loadedVersion;

    private ReloadablePriority(Path file, PriorityRules rules, String source, Clock clock) {
        this.file = file;
        this.clock = clock;
        this.state = new Snapshot(rules, source, clock.instant(), null);
    }

    /**
     * Règles d'un fichier JSON, relu dès qu'il change ; refuse de démarrer sur un fichier illisible ou invalide.
     *
     * @throws IllegalArgumentException si le fichier est illisible ou une règle invalide
     */
    public static ReloadablePriority fromFile(Path file, Clock clock) {
        ReloadablePriority priority = new ReloadablePriority(file, PriorityRules.NONE, "fichier " + file, clock);
        try {
            priority.reload(true);
        } catch (IOException e) {
            throw new IllegalArgumentException("Règles de priorité illisibles (" + file + ") : " + e.getMessage(), e);
        }
        return priority;
    }

    /** Règles fixées au démarrage (propriétés de la box). */
    public static ReloadablePriority fixed(PriorityRules rules, Clock clock) {
        ReloadablePriority priority = new ReloadablePriority(null, rules,
                rules.rules().isEmpty() ? "aucune règle" : "configuration", clock);
        priority.logRules();
        return priority;
    }

    /** Lit le document JSON. */
    static PriorityRules parse(String json) {
        Document document;
        try {
            document = JSON.readValue(json, Document.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("document JSON invalide : " + e.getOriginalMessage(), e);
        }
        List<PriorityRule> rules = new ArrayList<>();
        for (DocumentRule rule : document.rules() == null ? List.<DocumentRule>of() : document.rules()) {
            rules.add(new PriorityRule(rule.name(), rule.priority(), new DicomCondition(rule.and(), rule.or())));
        }
        return new PriorityRules(document.defaultPriority() == null ? SeriesPriority.DEFAULT
                : document.defaultPriority(), rules);
    }

    /** Relit le fichier s'il a changé. */
    private synchronized void reload(boolean strict) throws IOException {
        FileTime version = Files.getLastModifiedTime(file);
        if (version.equals(loadedVersion)) {
            return;
        }
        try {
            PriorityRules rules = parse(Files.readString(file));
            state = new Snapshot(rules, "fichier " + file, clock.instant(), null);
            loadedVersion = version;
            logRules();
        } catch (IllegalArgumentException e) {
            if (strict) {
                throw new IllegalArgumentException("Règles de priorité invalides (" + file + ") : " + e.getMessage(),
                        e);
            }
            loadedVersion = version;
            state = new Snapshot(state.rules(), state.source(), state.loadedAt(), e.getMessage());
            LOG.warn("Règles de priorité modifiées mais refusées, les précédentes restent en vigueur : {}",
                    e.getMessage());
        }
    }

    private void logRules() {
        PriorityRules rules = state.rules();
        LOG.info("Priorité des séries ({}) : par défaut {}, {} règle(s){}", state.source(), rules.defaultPriority(),
                rules.rules().size(), rules.describe().isEmpty() ? "" : " : " + String.join(" ; ", rules.describe()));
    }

    @Override
    public Snapshot snapshot() {
        return state();
    }

    /** Règles de la configuration centralisée (vide : règles locales). */
    public void central(java.util.function.Supplier<java.util.Optional<Snapshot>> central) {
        this.central = central;
    }

    /** Règles en vigueur : celles de la configuration centralisée, sinon les locales (fichier relu s'il a changé). */
    public Snapshot state() {
        java.util.Optional<Snapshot> centralState = central.get();
        if (centralState.isPresent()) {
            return centralState.get();
        }
        if (file != null) {
            try {
                reload(false);
            } catch (IOException e) {
                Snapshot s = state;
                state = new Snapshot(s.rules(), s.source(), s.loadedAt(), "fichier illisible : " + e.getMessage());
            }
        }
        return state;
    }

    @Override
    public int of(List<ReceivedInstance> series) {
        return state().rules().of(series);
    }
}
