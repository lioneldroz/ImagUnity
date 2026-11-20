package com.imagunity.directory;

import java.util.List;
import java.util.Optional;

/**
 * Java d'exécution trop ancien pour une faille connue : signalé au démarrage de la box, du relais, de l'annuaire et
 * de l'outil du responsable. Java est fourni par le système (box) ou par l'image (site central) : aucun contrôle de
 * dépendances ne le voit, et une box qui reçoit une nouvelle version signée garde le Java de son système.
 *
 * <p>Seule la version 25 est suivie, celle qu'exige ImagUnity ; une version plus récente n'est pas jugée.
 */
public final class JavaRuntimeCheck {

    /** Première version de Java 25 corrigée pour CVE-2026-114938 (« java4shell », compilateur JIT, CVSS 8,1). */
    public static final Runtime.Version MINIMUM = Runtime.Version.parse("25.0.5.1");

    private static final String CVE = "CVE-2026-114938";

    private JavaRuntimeCheck() {
    }

    /** Le message à afficher si cette version de Java est vulnérable ; vide sinon. */
    public static Optional<String> problem(Runtime.Version version) {
        if (version.feature() != MINIMUM.feature() || compare(version.version(), MINIMUM.version()) >= 0) {
            return Optional.empty();
        }
        return Optional.of("Java " + version + " est vulnérable (" + CVE + ", prise de contrôle à distance) : "
                + "mettre à jour Java en " + MINIMUM + " ou plus récent (box : dnf upgrade java-25-openjdk-headless, "
                + "puis redémarrer ; site central : reconstruire les images), voir les notes de version.");
    }

    /** Écrit l'avertissement dans le journal (erreur) si le Java en cours est vulnérable. */
    public static void warnIfVulnerable() {
        problem(Runtime.version()).ifPresent(message ->
                System.getLogger(JavaRuntimeCheck.class.getName()).log(System.Logger.Level.ERROR, message));
    }

    private static int compare(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }
}
