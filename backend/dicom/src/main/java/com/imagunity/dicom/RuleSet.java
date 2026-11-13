package com.imagunity.dicom;

import com.imagunity.domain.exam.SeriesPriority;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.VR;

/**
 * Règles de priorité et de routage décrites hors de la configuration locale (document de configuration centralisée) :
 * vérifiées et compilées une fois, puis appliquées par la box ou essayées par l'interface d'administration.
 *
 * @param priority règles de priorité, ou {@code null} : celles de la configuration locale
 * @param routing  règles de routage, ou {@code null} : celles de la configuration locale
 */
public record RuleSet(PriorityRules priority, List<RoutingRule> routing) {

    /**
     * Description d'une règle : {@code priority} pour une règle de priorité, {@code to} pour une règle de routage.
     */
    public record Spec(String name, Integer priority, Map<String, String> and, Map<String, String> or,
                       List<String> to) {
    }

    public RuleSet {
        routing = routing == null ? null : List.copyOf(routing);
    }

    /**
     * Vérifie et compile des règles.
     *
     * @param defaultPriority priorité par défaut, ou {@code null} : 5
     * @param priority        règles de priorité, ou {@code null} si le document n'en porte pas
     * @param routing         règles de routage, ou {@code null} si le document n'en porte pas
     * @throws IllegalArgumentException à la première règle invalide (mot-clé inconnu, motif absent, partenaire
     *                                  invalide…), avec un message explicite
     */
    public static RuleSet compile(Integer defaultPriority, List<Spec> priority, List<Spec> routing) {
        PriorityRules priorityRules = null;
        if (priority != null) {
            List<PriorityRule> rules = new ArrayList<>();
            for (Spec spec : priority) {
                if (spec.priority() == null) {
                    throw new IllegalArgumentException("Règle de priorité " + spec.name() + " sans priorité");
                }
                rules.add(new PriorityRule(spec.name(), spec.priority(), new DicomCondition(spec.and(), spec.or())));
            }
            priorityRules = new PriorityRules(defaultPriority == null ? SeriesPriority.DEFAULT : defaultPriority,
                    rules);
        }
        List<RoutingRule> routingRules = null;
        if (routing != null) {
            routingRules = new ArrayList<>();
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Spec spec : routing) {
                RoutingRule rule = new RoutingRule(spec.name(), new DicomCondition(spec.and(), spec.or()), spec.to());
                if (!names.add(rule.name())) {
                    throw new IllegalArgumentException("Deux règles de routage nommées « " + rule.name() + " »");
                }
                routingRules.add(rule);
            }
        }
        return new RuleSet(priorityRules, routingRules);
    }

    /**
     * Résultat d'un essai sur un en-tête.
     *
     * @param priority         priorité de la série, ou 0 sans règles de priorité
     * @param priorityRule     règle de priorité appliquée, ou {@code null} (priorité par défaut)
     * @param routingRule      règle de routage appliquée, ou {@code null}
     * @param destinations     partenaires désignés par le routage (vide : aucune règle ne correspond)
     */
    public record Trial(int priority, String priorityRule, String routingRule, List<String> destinations) {
    }

    /** Essaie les règles sur un en-tête DICOM (données synthétiques). */
    public Trial trial(Attributes dataset) {
        int value = 0;
        String priorityRule = null;
        if (priority != null) {
            value = priority.defaultPriority();
            for (PriorityRule rule : priority.rules()) {
                if (rule.matches(dataset)) {
                    value = rule.priority();
                    priorityRule = rule.name();
                    break;
                }
            }
        }
        Optional<RoutingRule> routingRule = routing == null ? Optional.empty()
                : routing.stream().filter(r -> r.matches(dataset)).findFirst();
        return new Trial(value, priorityRule, routingRule.map(RoutingRule::name).orElse(null),
                routingRule.map(RoutingRule::destinations).orElse(List.of()));
    }

    /**
     * En-tête DICOM d'essai à partir de mots-clés : {@code Modality=CT} ; plusieurs valeurs séparées par {@code \}.
     *
     * @throws IllegalArgumentException si un mot-clé est inconnu
     */
    public static Attributes dataset(Map<String, String> fields) {
        Attributes dataset = new Attributes();
        fields.forEach((keyword, value) -> {
            int tag = DicomCondition.tagOf(keyword, "En-tête d'essai");
            VR vr = ElementDictionary.vrOf(tag, null);
            if (vr == VR.SQ) {
                throw new IllegalArgumentException("En-tête d'essai : séquence non prise en charge (" + keyword + ")");
            }
            dataset.setString(tag, vr, value == null ? new String[0] : value.split("\\\\", -1));
        });
        return dataset;
    }
}
