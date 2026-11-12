package com.imagunity.dicom;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;

/**
 * Règle de routage par champ DICOM : si chaque condition est remplie, l'instance part vers les partenaires désignés.
 *
 * <p>Une condition associe un mot-clé DICOM ({@code Modality}, {@code InstitutionName}, {@code StudyDescription}…)
 * à un motif : {@code *} remplace n'importe quelle suite de caractères, {@code ?} un seul caractère ; la casse et les
 * espaces autour de la valeur sont ignorés. Un champ à plusieurs valeurs (par exemple {@code ImageType}) remplit la
 * condition si l'une de ses valeurs correspond ; un champ absent vaut la chaîne vide. Un motif qui commence par
 * {@code !} est une négation : {@code !SR} retient tout sauf {@code SR}.
 *
 * @param name         nom de la règle (journaux)
 * @param when         conditions : mot-clé DICOM → motif (aucune condition : toujours vraie)
 * @param destinations partenaires destinataires, au moins un
 */
public record RoutingRule(String name, Map<String, String> when, List<String> destinations) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    public RoutingRule {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Nom de règle de routage invalide : « " + name + " »");
        }
        when = when == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(when));
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
        when.forEach((keyword, pattern) -> {
            tagOf(keyword, name);
            Objects.requireNonNull(pattern, "Règle de routage " + name + " : motif absent pour " + keyword);
        });
    }

    /** Vrai si l'instance remplit toutes les conditions. */
    public boolean matches(Attributes dataset) {
        for (Map.Entry<String, String> condition : when.entrySet()) {
            if (!conditionHolds(dataset, tagOf(condition.getKey(), name), condition.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean conditionHolds(Attributes dataset, int tag, String rawPattern) {
        boolean negated = rawPattern.startsWith("!");
        Pattern pattern = glob(negated ? rawPattern.substring(1) : rawPattern);
        String[] values = dataset.getStrings(tag);
        boolean any = false;
        if (values == null || values.length == 0) {
            any = pattern.matcher("").matches();
        } else {
            for (String value : values) {
                if (pattern.matcher(value == null ? "" : value.strip()).matches()) {
                    any = true;
                    break;
                }
            }
        }
        return negated != any;
    }

    /** Motif {@code *} / {@code ?}, sans tenir compte de la casse. */
    static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.strip().toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    /** Mots-clés DICOM du dictionnaire, en minuscules → étiquette ; la casse est ignorée (variables d'environnement). */
    private static final Map<String, Integer> KEYWORDS = keywords();

    private static Map<String, Integer> keywords() {
        Map<String, Integer> keywords = new java.util.HashMap<>();
        for (java.lang.reflect.Field field : Tag.class.getFields()) {
            if (field.getType() == int.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {
                    keywords.putIfAbsent(field.getName().toLowerCase(Locale.ROOT), field.getInt(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return Map.copyOf(keywords);
    }

    private static int tagOf(String keyword, String rule) {
        Integer tag = keyword == null ? null : KEYWORDS.get(keyword.strip().toLowerCase(Locale.ROOT));
        if (tag == null) {
            throw new IllegalArgumentException("Règle de routage " + rule + " : mot-clé DICOM inconnu « " + keyword
                    + " » (par exemple Modality, InstitutionName, StudyDescription)");
        }
        return tag;
    }

    /** Résumé lisible, sans valeur de patient : {@code nom (Modality=CT, …) → box-b, box-c}. */
    public String describe() {
        String conditions = when.isEmpty() ? "toujours" : String.join(", ", when.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).toList());
        return name + " (" + conditions + ") → " + String.join(", ", destinations);
    }
}
