package com.imagunity.config;

import com.imagunity.dicom.DicomCondition;
import com.imagunity.dicom.PriorityRule;
import com.imagunity.dicom.PriorityRules;
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
public final class ReloadablePriority implements SeriesPriority {

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

    /**
     * État présenté à la console.
     *
     * @param rules    règles en vigueur
     * @param source   « fichier &lt;chemin&gt; », « configuration » ou « aucune règle »
     * @param loadedAt chargement des règles en vigueur
     * @param error    dernier refus d'un fichier invalide, ou {@code null}
     */
    public record State(PriorityRules rules, String source, Instant loadedAt, String error) {
    }

    private final Path file;
    private final Clock clock;
    private volatile State state;
    private FileTime loadedVersion;

    private ReloadablePriority(Path file, PriorityRules rules, String source, Clock clock) {
        this.file = file;
        this.clock = clock;
        this.state = new State(rules, source, clock.instant(), null);
    }

    /**
     * Règles d'après la configuration ; refuse de démarrer sur une règle ou un fichier invalide.
     *
     * @throws IllegalArgumentException si une règle est invalide (mot-clé inconnu, priorité hors de 1 à 9…)
     */
    static ReloadablePriority of(PriorityProperties properties, Clock clock) {
        if (properties.file() != null && !properties.file().toString().isBlank()) {
            ReloadablePriority priority = new ReloadablePriority(properties.file(), PriorityRules.NONE,
                    "fichier " + properties.file(), clock);
            try {
                priority.reload(true);
            } catch (IOException e) {
                throw new IllegalArgumentException("Règles de priorité illisibles (" + properties.file() + ") : "
                        + e.getMessage(), e);
            }
            return priority;
        }
        List<PriorityRule> rules = new ArrayList<>();
        for (PriorityProperties.Rule rule : properties.rules()) {
            rules.add(new PriorityRule(rule.name(), rule.priority(), new DicomCondition(rule.and(), rule.or())));
        }
        PriorityRules built = new PriorityRules(properties.defaultPriority(), rules);
        ReloadablePriority priority = new ReloadablePriority(null, built,
                rules.isEmpty() ? "aucune règle" : "configuration", clock);
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
            state = new State(rules, "fichier " + file, clock.instant(), null);
            loadedVersion = version;
            logRules();
        } catch (IllegalArgumentException e) {
            if (strict) {
                throw new IllegalArgumentException("Règles de priorité invalides (" + file + ") : " + e.getMessage(),
                        e);
            }
            loadedVersion = version;
            state = new State(state.rules(), state.source(), state.loadedAt(), e.getMessage());
            LOG.warn("Règles de priorité modifiées mais refusées, les précédentes restent en vigueur : {}",
                    e.getMessage());
        }
    }

    private void logRules() {
        PriorityRules rules = state.rules();
        LOG.info("Priorité des séries ({}) : par défaut {}, {} règle(s){}", state.source(), rules.defaultPriority(),
                rules.rules().size(), rules.describe().isEmpty() ? "" : " : " + String.join(" ; ", rules.describe()));
    }

    /** Règles en vigueur, après relecture du fichier s'il a changé. */
    public State state() {
        if (file != null) {
            try {
                reload(false);
            } catch (IOException e) {
                State s = state;
                state = new State(s.rules(), s.source(), s.loadedAt(), "fichier illisible : " + e.getMessage());
            }
        }
        return state;
    }

    @Override
    public int of(List<ReceivedInstance> series) {
        return state().rules().of(series);
    }
}
