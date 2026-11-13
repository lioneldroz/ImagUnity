package com.imagunity.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registre des versions livrées d'un produit ({@code deploy/livraison/registre-versions.json}), tenu dans le dépôt :
 * l'outil de l'éditeur y prend le numéro suivant et y inscrit chaque version qu'il signe. Il garantit qu'un numéro
 * n'est ni réutilisé ni sauté, et qu'une version n'est signée qu'une fois, plus récente que la précédente.
 *
 * @param product       produit ({@code imagunity-box})
 * @param firstSequence premier numéro attribué quand le registre est vide
 * @param note          remarque libre (historique des numéros…)
 * @param versions      versions signées, dans l'ordre
 */
public record VersionRegistry(String product, long firstSequence, String note, List<Entry> versions) {

    /** Version livrée : {@code X.Y.Z}, sans suffixe. */
    private static final Pattern RELEASE = Pattern.compile("(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /**
     * Version signée.
     *
     * @param version  version ({@code X.Y.Z})
     * @param sequence numéro
     * @param sha256   empreinte de l'artefact signé
     * @param signedAt date de signature
     */
    public record Entry(String version, long sequence, String sha256, Instant signedAt) {
    }

    public VersionRegistry {
        Objects.requireNonNull(product, "product");
        if (firstSequence < 1) {
            throw new ReleaseException("Registre : premier numéro invalide " + firstSequence);
        }
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    public static VersionRegistry read(Path file) throws IOException {
        try {
            VersionRegistry registry = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    VersionRegistry.class);
            if (registry == null) {
                throw new ReleaseException("Registre des versions vide : " + file);
            }
            return registry;
        } catch (JacksonException e) {
            throw new ReleaseException("Registre des versions illisible : " + e.getOriginalMessage(), e);
        }
    }

    /** Écrit le registre d'un coup (fichier temporaire, puis remplacement). */
    public void write(Path file) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".nouveau");
        Files.writeString(temporary, MAPPER.writeValueAsString(this) + "\n", StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Numéro de la prochaine version : le suivant du plus haut inscrit, sinon {@code firstSequence}. */
    public long nextSequence() {
        return versions.stream().mapToLong(Entry::sequence).max().stream().map(n -> n + 1).findFirst()
                .orElse(firstSequence);
    }

    /** Dernière version inscrite (numéro le plus haut). */
    public Optional<Entry> last() {
        return versions.stream().max(Comparator.comparingLong(Entry::sequence));
    }

    /**
     * Vérifie qu'une version peut être signée sous le numéro suivant.
     *
     * @throws ReleaseException avec la raison, sinon
     */
    public void checkNew(String productName, String version, String sha256) {
        if (!product.equals(productName)) {
            throw new ReleaseException("Registre de « " + product + " », pas de « " + productName + " »");
        }
        if (!RELEASE.matcher(version).matches()) {
            throw new ReleaseException("Version « " + version + " » non livrable : X.Y.Z attendu, sans -SNAPSHOT "
                    + "(deploy/livraison/fixer-version.sh)");
        }
        for (Entry entry : versions) {
            if (entry.version().equals(version)) {
                throw new ReleaseException("Version " + version + " déjà signée (n° " + entry.sequence() + ")");
            }
            if (entry.sha256().equalsIgnoreCase(sha256)) {
                throw new ReleaseException("Artefact déjà signé comme version " + entry.version() + " (n° "
                        + entry.sequence() + ")");
            }
        }
        Optional<Entry> last = last();
        if (last.isPresent() && compare(version, last.get().version()) <= 0) {
            throw new ReleaseException("Version " + version + " pas plus récente que la dernière livrée ("
                    + last.get().version() + ")");
        }
    }

    /** Registre avec une version de plus. */
    public VersionRegistry with(Entry entry) {
        List<Entry> all = new ArrayList<>(versions);
        all.add(entry);
        return new VersionRegistry(product, firstSequence, note, all);
    }

    /** Compare deux versions {@code X.Y.Z} numériquement. */
    static int compare(String a, String b) {
        Matcher left = RELEASE.matcher(a);
        Matcher right = RELEASE.matcher(b);
        if (!left.matches() || !right.matches()) {
            throw new ReleaseException("Versions non comparables : " + a + ", " + b);
        }
        for (int i = 1; i <= 3; i++) {
            int c = Long.compare(Long.parseLong(left.group(i)), Long.parseLong(right.group(i)));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Version portée par un jar ({@code Implementation-Version} de son manifeste), si elle y est. */
    static Optional<String> jarVersion(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return Optional.ofNullable(file.getManifest())
                    .map(m -> m.getMainAttributes().getValue("Implementation-Version"))
                    .filter(v -> !v.isBlank());
        } catch (java.util.zip.ZipException e) {
            return Optional.empty();
        }
    }
}
