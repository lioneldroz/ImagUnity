package com.imagunity.dicom;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.dcm4che3.data.Attributes;

/**
 * Règle de routage par champ DICOM : si l'instance remplit la condition ({@link DicomCondition} : champs {@code and},
 * {@code or}, motifs avec {@code *}, {@code ?}, {@code |} et {@code !}), elle part vers les partenaires désignés.
 *
 * @param name         nom de la règle (journaux)
 * @param condition    condition sur les champs de l'instance
 * @param destinations partenaires destinataires, au moins un
 */
public record RoutingRule(String name, DicomCondition condition, List<String> destinations) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    public RoutingRule {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Nom de règle de routage invalide : « " + name + " »");
        }
        condition = condition == null ? DicomCondition.ALWAYS
                : DicomCondition.of("Règle de routage " + name, condition.and(), condition.or());
        destinations = destinations == null ? List.of() : List.copyOf(destinations);
        if (destinations.isEmpty()) {
            throw new IllegalArgumentException("Règle de routage " + name + " sans destinataire");
        }
        for (String destination : destinations) {
            DicomInputConfig.requireDestination(destination, "Règle de routage " + name);
        }
        if (destinations.stream().distinct().count() != destinations.size()) {
            throw new IllegalArgumentException("Règle de routage " + name + " : destinataire en double");
        }
    }

    /** Règle dont tous les champs doivent correspondre ({@code and}). */
    public RoutingRule(String name, Map<String, String> and, List<String> destinations) {
        this(name, new DicomCondition(and, Map.of()), destinations);
    }

    /** Vrai si l'instance remplit la condition. */
    public boolean matches(Attributes dataset) {
        return condition.matches(dataset);
    }

    /** Résumé lisible, sans valeur de patient : {@code nom (Modality=CT, …) → box-b, box-c}. */
    public String describe() {
        return name + " (" + condition.describe() + ") → " + String.join(", ", destinations);
    }
}
