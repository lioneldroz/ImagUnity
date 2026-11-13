package com.imagunity.rules;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.SeriesPriority;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.io.DicomInputStream;

/**
 * Règles de priorité des séries, lues dans l'ordre : la première qui correspond donne la priorité ; aucune ne
 * correspond : la priorité par défaut.
 *
 * <p>La priorité d'une série est évaluée sur l'en-tête de chacune de ses instances (lu sans les pixels) ; si elles
 * divergent (un {@code ImageType} différent, par exemple), la plus haute l'emporte. Une instance illisible compte pour
 * la priorité par défaut : elle sera refusée plus loin, à l'empaquetage ou à la restitution.
 *
 * @param defaultPriority priorité des séries qu'aucune règle ne couvre, de 1 à 9
 * @param rules           règles, dans l'ordre
 */
public record PriorityRules(int defaultPriority, List<PriorityRule> rules) implements SeriesPriority {

    private static final System.Logger LOG = System.getLogger(PriorityRules.class.getName());

    /** Sans règle. */
    public static final PriorityRules NONE = new PriorityRules(SeriesPriority.DEFAULT, List.of());

    public PriorityRules {
        if (defaultPriority < SeriesPriority.HIGHEST || defaultPriority > SeriesPriority.LOWEST) {
            throw new IllegalArgumentException("Priorité par défaut " + defaultPriority + " hors de 1 à 9");
        }
        rules = rules == null ? List.of() : List.copyOf(rules);
        Set<String> names = new HashSet<>();
        for (PriorityRule rule : rules) {
            if (!names.add(rule.name())) {
                throw new IllegalArgumentException("Deux règles de priorité nommées « " + rule.name() + " »");
            }
        }
    }

    /** Priorité d'une instance d'après son en-tête. */
    public int priorityOf(Attributes dataset) {
        for (PriorityRule rule : rules) {
            if (rule.matches(dataset)) {
                return rule.priority();
            }
        }
        return defaultPriority;
    }

    @Override
    public int of(List<ReceivedInstance> series) {
        if (rules.isEmpty()) {
            return defaultPriority;
        }
        int best = SeriesPriority.LOWEST + 1;
        for (ReceivedInstance instance : series) {
            int p;
            try (DicomInputStream in = new DicomInputStream(instance.file().toFile())) {
                p = priorityOf(in.readDatasetUntilPixelData());
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "En-tête d''une instance illisible pour la priorité : {0}", e.getMessage());
                p = defaultPriority;
            }
            best = Math.min(best, p);
            if (best == SeriesPriority.HIGHEST) {
                break;
            }
        }
        return best > SeriesPriority.LOWEST ? defaultPriority : best;
    }

    /** Résumé des règles pour les journaux, sans valeur de patient. */
    public List<String> describe() {
        return rules.stream().map(PriorityRule::describe).toList();
    }
}
