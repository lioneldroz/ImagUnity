package com.imagunity.directory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versions du protocole d'échange entre box (étape 5 de la fédération). Chaque entrée d'annuaire annonce les versions
 * que parle la box ({@code "protocols": ["iu1.0", "iu1.1"]}) ; deux box échangent dans la plus haute version
 * commune, et refusent proprement s'il n'y en a pas. Règle : aucun changement du format des échanges sans nouvelle
 * version annoncée ici.
 *
 * <p>Une entrée sans le champ (box antérieure aux versions) parle {@code iu1.0}.
 */
public final class Protocols {

    /** Version d'une box qui n'en annonce pas. */
    public static final String INITIAL = "iu1.0";
    /** Versions parlées par ce logiciel, de la plus ancienne à la plus récente. */
    public static final List<String> SUPPORTED = List.of(INITIAL);
    /** Versions annoncées au plus par une entrée. */
    static final int MAX = 8;

    private static final Pattern VERSION = Pattern.compile("iu([1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})");
    private static final Comparator<String> ORDER = Comparator.comparingInt((String v) -> part(v, 1))
            .thenComparingInt(v -> part(v, 2));

    private Protocols() {
    }

    private static int part(String version, int group) {
        Matcher m = VERSION.matcher(version);
        if (!m.matches()) {
            throw new IllegalArgumentException("Version de protocole invalide « " + version + " » (iu<majeure>.<mineure>)");
        }
        return Integer.parseInt(m.group(group));
    }

    /**
     * Vérifie et ordonne des versions annoncées (de la plus ancienne à la plus récente). Vide ou absent : vide (la
     * version initiale, voir {@link #announced}).
     *
     * @throws IllegalArgumentException version mal formée, en double, ou trop de versions
     */
    public static List<String> of(Collection<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        TreeSet<String> sorted = new TreeSet<>(ORDER);
        for (String version : versions) {
            if (version == null) {
                throw new IllegalArgumentException("Version de protocole absente");
            }
            part(version, 1);
            if (!sorted.add(version)) {
                throw new IllegalArgumentException("Version de protocole en double : " + version);
            }
        }
        if (sorted.size() > MAX) {
            throw new IllegalArgumentException("Trop de versions de protocole (" + sorted.size() + ", " + MAX
                    + " au plus)");
        }
        return List.copyOf(sorted);
    }

    /** Versions effectivement annoncées : celles données, ou la version initiale si aucune. */
    public static List<String> announced(List<String> versions) {
        return versions == null || versions.isEmpty() ? List.of(INITIAL) : versions;
    }

    /** Plus haute version commune aux deux listes annoncées, s'il y en a une. */
    public static Optional<String> negotiate(List<String> mine, List<String> theirs) {
        List<String> common = new ArrayList<>(announced(mine));
        common.retainAll(announced(theirs));
        return common.stream().max(ORDER);
    }

    /** Motif du refus si les deux listes n'ont aucune version commune ; vide sinon. */
    public static Optional<String> refusal(List<String> mine, List<String> theirs, String other) {
        if (negotiate(mine, theirs).isPresent()) {
            return Optional.empty();
        }
        return Optional.of("Aucune version du protocole en commun avec « " + other + " » : cette box parle "
                + String.join(", ", announced(mine)) + ", l'autre " + String.join(", ", announced(theirs))
                + " ; mettre à jour la plus ancienne");
    }
}
