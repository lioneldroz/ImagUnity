package com.imagunity.rules;

import com.imagunity.domain.exam.SeriesPriority;
import java.util.regex.Pattern;
import org.dcm4che3.data.Attributes;

/**
 * Règle de priorité par champ DICOM : une série dont une instance remplit la condition reçoit la priorité de la règle.
 *
 * @param name      nom de la règle (journaux, console)
 * @param priority  de 1 (la plus haute) à 9
 * @param condition condition sur les champs de l'instance ({@link DicomCondition})
 */
public record PriorityRule(String name, int priority, DicomCondition condition) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    public PriorityRule {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Nom de règle de priorité invalide : « " + name + " »");
        }
        if (priority < SeriesPriority.HIGHEST || priority > SeriesPriority.LOWEST) {
            throw new IllegalArgumentException("Règle de priorité " + name + " : priorité " + priority
                    + " hors de 1 à 9");
        }
        condition = condition == null ? DicomCondition.ALWAYS
                : DicomCondition.of("Règle de priorité " + name, condition.and(), condition.or());
    }

    public boolean matches(Attributes dataset) {
        return condition.matches(dataset);
    }

    /** Résumé lisible, sans valeur de patient : {@code nom : priorité 1 (Modality=CT, …)}. */
    public String describe() {
        return name + " : priorité " + priority + " (" + condition.describe() + ")";
    }
}
