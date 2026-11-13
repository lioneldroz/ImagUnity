package com.imagunity.config;

import com.imagunity.configuration.ReloadablePriority;
import com.imagunity.rules.DicomCondition;
import com.imagunity.rules.PriorityRule;
import com.imagunity.rules.PriorityRules;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Priorité des séries ({@code imagunity.priority.*}) : règles par champ DICOM, lues dans l'ordre.
 *
 * <p>Deux sources : un <b>fichier JSON</b> ({@code file}), au format du document de configuration centralisée prévu
 * ({@code docs/configuration-centralisee.md}) et rechargé dès qu'il change ; ou ces propriétés (variables
 * d'environnement {@code IMAGUNITY_PRIORITY_…}), fixées au démarrage. Le fichier l'emporte s'il est indiqué.
 *
 * @param defaultPriority priorité des séries qu'aucune règle ne couvre (1 à 9)
 * @param file            document JSON des règles, rechargé à chaud (vide : propriétés)
 * @param rules           règles, dans l'ordre
 */
@ConfigurationProperties("imagunity.priority")
public record PriorityProperties(
        @DefaultValue("5") int defaultPriority,
        Path file,
        @DefaultValue List<Rule> rules) {

    /**
     * Règle de priorité.
     *
     * @param name     nom de la règle (journaux, console)
     * @param priority de 1 (la plus haute) à 9
     * @param and      champs qui doivent tous correspondre : mot-clé DICOM → motif
     * @param or       champs dont un seul suffit
     */
    public record Rule(String name, int priority, Map<String, String> and, Map<String, String> or) {
    }

    /**
     * Règles en vigueur d'après ces propriétés ; refuse de démarrer sur une règle ou un fichier invalide.
     *
     * @throws IllegalArgumentException si une règle est invalide (mot-clé inconnu, priorité hors de 1 à 9…)
     */
    ReloadablePriority priority(Clock clock) {
        if (file != null && !file.toString().isBlank()) {
            return ReloadablePriority.fromFile(file, clock);
        }
        List<PriorityRule> built = new ArrayList<>();
        for (Rule rule : rules) {
            built.add(new PriorityRule(rule.name(), rule.priority(), new DicomCondition(rule.and(), rule.or())));
        }
        return ReloadablePriority.fixed(new PriorityRules(defaultPriority, built), clock);
    }
}
