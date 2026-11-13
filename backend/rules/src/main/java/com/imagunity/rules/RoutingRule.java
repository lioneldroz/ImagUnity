package com.imagunity.rules;

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
    /** Nom de partenaire (comme les contacts appairés) : sert aussi de nom de dossier. */
    private static final Pattern DESTINATION = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

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
            requireDestination(destination, "Règle de routage " + name);
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

    /**
     * Vérifie un nom de partenaire désigné par une destination ou une règle.
     *
     * @throws IllegalArgumentException si le nom est invalide
     */
    public static void requireDestination(String destination, String what) {
        if (destination == null || !DESTINATION.matcher(destination).matches()) {
            throw new IllegalArgumentException(what + " : partenaire invalide « " + destination
                    + " » (1 à 64 caractères parmi A-Z, a-z, 0-9, _ et -)");
        }
    }
}
