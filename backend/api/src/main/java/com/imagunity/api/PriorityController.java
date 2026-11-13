package com.imagunity.api;

import com.imagunity.rules.DicomCondition;
import com.imagunity.rules.PriorityRule;
import com.imagunity.rules.PrioritySource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Règles de priorité des séries en vigueur, en lecture seule (les règles se modifient dans la configuration de la box ;
 * plus tard depuis le serveur central, voir {@code docs/reference/configuration-centralisee.md}).
 *
 * <pre>
 * GET /api/v1/priority → {"source", "loadedAt", "error", "defaultPriority", "rules": [{"name", "priority", "and", "or"}]}
 * </pre>
 * Les règles ne contiennent que des motifs de configuration, jamais de valeur lue dans les instances.
 */
@RestController
@RequestMapping("/api/v1/priority")
public class PriorityController {

    /** Une règle : nom, priorité (1 = la plus haute) et conditions. */
    public record RuleView(String name, int priority, Map<String, String> and, Map<String, String> or) {

        static RuleView of(PriorityRule rule) {
            DicomCondition c = rule.condition();
            return new RuleView(rule.name(), rule.priority(), c.and(), c.or());
        }
    }

    /** Règles en vigueur et leur provenance. */
    public record PriorityView(String source, Instant loadedAt, String error, int defaultPriority,
                               List<RuleView> rules) {
    }

    private final ObjectProvider<PrioritySource> source;

    public PriorityController(ObjectProvider<PrioritySource> source) {
        this.source = source;
    }

    @GetMapping
    public PriorityView priority() {
        PrioritySource current = source.getIfAvailable();
        if (current == null) {
            return new PriorityView("aucune règle", null, null, com.imagunity.domain.exam.SeriesPriority.DEFAULT,
                    List.of());
        }
        PrioritySource.Snapshot s = current.snapshot();
        return new PriorityView(s.source(), s.loadedAt(), s.error(), s.rules().defaultPriority(),
                s.rules().rules().stream().map(RuleView::of).toList());
    }
}
