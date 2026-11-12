package com.imagunity.dicom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;

/**
 * Condition sur les champs DICOM d'une instance, commune aux règles de routage et de priorité.
 *
 * <ul>
 *   <li>{@code and} : tous les champs doivent correspondre ; {@code or} : un seul suffit ; les deux ensemble : tous
 *       ceux de {@code and} et au moins un de {@code or}. Sans champ : toujours vraie.</li>
 *   <li>Un champ est un mot-clé du dictionnaire DICOM ({@code Modality}, {@code SeriesDescription}…), sans tenir
 *       compte de la casse.</li>
 *   <li>Motif : valeur exacte ({@code CT}), {@code *} pour n'importe quelle suite de caractères ({@code CHU*} :
 *       commence par ; {@code *THORAX*} : contient), {@code ?} pour un caractère ; {@code |} sépare des valeurs
 *       possibles ({@code *THORAX*|*POUMON*}) ; un {@code !} initial inverse le tout ({@code !SR|KO}). La casse et les
 *       espaces autour de la valeur sont ignorés.</li>
 *   <li>Un champ à plusieurs valeurs ({@code ImageType}) correspond si l'une d'elles correspond ; un champ absent
 *       vaut la chaîne vide ({@code ""} : absent ; {@code "!"} : présent).</li>
 * </ul>
 *
 * @param and champs qui doivent tous correspondre : mot-clé → motif
 * @param or  champs dont un seul suffit
 */
public record DicomCondition(Map<String, String> and, Map<String, String> or) {

    /** Toujours vraie. */
    public static final DicomCondition ALWAYS = new DicomCondition(Map.of(), Map.of());

    public DicomCondition {
        and = copy(and);
        or = copy(or);
    }

    private static Map<String, String> copy(Map<String, String> fields) {
        return fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * Condition vérifiée : mots-clés connus, motifs présents.
     *
     * @param context début des messages d'erreur, par exemple « Règle de routage scanner-chu »
     * @throws IllegalArgumentException si un mot-clé est inconnu ou un motif absent
     */
    public static DicomCondition of(String context, Map<String, String> and, Map<String, String> or) {
        DicomCondition condition = new DicomCondition(and, or);
        condition.check(context);
        return condition;
    }

    private void check(String context) {
        for (Map<String, String> fields : List.of(and, or)) {
            fields.forEach((keyword, pattern) -> {
                tagOf(keyword, context);
                if (pattern == null) {
                    throw new IllegalArgumentException(context + " : motif absent pour " + keyword);
                }
            });
        }
    }

    /** Vrai si l'instance remplit la condition. */
    public boolean matches(Attributes dataset) {
        for (Map.Entry<String, String> field : and.entrySet()) {
            if (!holds(dataset, field.getKey(), field.getValue())) {
                return false;
            }
        }
        if (or.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> field : or.entrySet()) {
            if (holds(dataset, field.getKey(), field.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean holds(Attributes dataset, String keyword, String rawPattern) {
        boolean negated = rawPattern.startsWith("!");
        List<Pattern> alternatives = new ArrayList<>();
        for (String alternative : (negated ? rawPattern.substring(1) : rawPattern).split("\\|", -1)) {
            alternatives.add(glob(alternative));
        }
        String[] values = dataset.getStrings(tagOf(keyword, "Condition DICOM"));
        boolean any = false;
        if (values == null || values.length == 0) {
            any = alternatives.stream().anyMatch(p -> p.matcher("").matches());
        } else {
            outer:
            for (String value : values) {
                String clean = value == null ? "" : value.strip();
                for (Pattern alternative : alternatives) {
                    if (alternative.matcher(clean).matches()) {
                        any = true;
                        break outer;
                    }
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

    static int tagOf(String keyword, String context) {
        Integer tag = keyword == null ? null : KEYWORDS.get(keyword.strip().toLowerCase(Locale.ROOT));
        if (tag == null) {
            throw new IllegalArgumentException(context + " : mot-clé DICOM inconnu « " + keyword
                    + " » (par exemple Modality, InstitutionName, SeriesDescription)");
        }
        return tag;
    }

    /**
     * Résumé lisible, sans valeur de patient : {@code Modality=CT, InstitutionName=CHU*}, puis
     * {@code ou (ImageType=*LOCALIZER* | SeriesDescription=*SCOUT*)} ; {@code toujours} sans champ.
     */
    public String describe() {
        List<String> parts = new ArrayList<>();
        and.forEach((k, v) -> parts.add(k + "=" + v));
        if (!or.isEmpty()) {
            List<String> any = new ArrayList<>();
            or.forEach((k, v) -> any.add(k + "=" + v));
            parts.add("ou (" + String.join(" | ", any) + ")");
        }
        return parts.isEmpty() ? "toujours" : String.join(", ", parts);
    }
}
