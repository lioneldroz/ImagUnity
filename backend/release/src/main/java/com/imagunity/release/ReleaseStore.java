package com.imagunity.release;

import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.SignedDocument;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Versions du logiciel de la box, côté box : versions installées, dépôt manuel, téléchargement, choix au démarrage.
 *
 * <pre>
 * &lt;dossier&gt;/etat.json                       dernière version exécutée (anti-retour en arrière)
 * &lt;dossier&gt;/versions/&lt;n°&gt;/manifeste.json     version installée : manifeste signé
 * &lt;dossier&gt;/versions/&lt;n°&gt;/&lt;artefact&gt;         et son artefact
 * &lt;dossier&gt;/depot/                          dépôt manuel : &lt;artefact&gt; et &lt;artefact&gt;.manifeste.json
 * &lt;dossier&gt;/depot/refusees/                 pièces déposées refusées, gardées pour examen
 * </pre>
 *
 * <p>Rien n'est installé sans avoir été vérifié ; une version est installée d'un coup (dossier renommé). Au démarrage,
 * la version valide de numéro le plus haut est choisie, entre la version livrée avec l'image et les versions installées
 * ; une version de numéro inférieur à la dernière exécutée est refusée.
 */
public final class ReleaseStore {

    /** Suffixe du manifeste signé d'un artefact. */
    public static final String MANIFEST_SUFFIX = ".manifeste.json";
    static final String LATEST = "latest.json";

    private final Path dir;
    private final ReleaseVerifier verifier;
    private final Clock clock;
    private final Consumer<String> log;

    /**
     * @param log messages pour l'exploitant
     */
    public ReleaseStore(Path dir, ReleaseVerifier verifier, Clock clock, Consumer<String> log) {
        this.dir = dir;
        this.verifier = verifier;
        this.clock = clock;
        this.log = log;
    }

    /**
     * Dernière version exécutée.
     *
     * @param sequence numéro de version
     * @param version  version affichée
     * @param at       date du premier démarrage sur cette version
     */
    public record Installed(long sequence, String version, Instant at) {
    }

    /**
     * Version retenue pour démarrer.
     *
     * @param manifest manifeste vérifié
     * @param jar      artefact vérifié
     */
    public record Choice(ReleaseManifest manifest, Path jar) {
    }

    private record Candidate(ReleaseManifest manifest, Path jar, String origin) {
    }

    public Optional<Installed> installed() throws IOException {
        Path file = dir.resolve("etat.json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(ReleaseJson.read(Files.readString(file, StandardCharsets.UTF_8), Installed.class,
                "État des mises à jour"));
    }

    // --- Arrivée de nouvelles versions ---

    /** Installe les versions déposées à la main dans {@code depot/} ; les pièces refusées sont mises de côté. */
    public void importDepot() throws IOException {
        Path depot = dir.resolve("depot");
        if (!Files.isDirectory(depot)) {
            return;
        }
        List<Path> manifests;
        try (Stream<Path> files = Files.list(depot)) {
            manifests = files.filter(p -> p.getFileName().toString().endsWith(MANIFEST_SUFFIX)).sorted().toList();
        }
        for (Path manifestFile : manifests) {
            String name = manifestFile.getFileName().toString();
            Path artifact = depot.resolve(name.substring(0, name.length() - MANIFEST_SUFFIX.length()));
            try {
                SignedDocument document = SignedDocument.fromJson(Files.readString(manifestFile,
                        StandardCharsets.UTF_8));
                ReleaseManifest manifest = verifier.verifyManifest(document);
                if (!manifest.artifact().name().equals(artifact.getFileName().toString())) {
                    throw new ReleaseException("Manifeste d'un autre artefact : " + manifest.artifact().name());
                }
                install(document, artifact);
                Files.delete(manifestFile);
                Files.delete(artifact);
                log.accept("Version " + manifest.label() + " déposée : installée");
            } catch (ReleaseException | DirectoryException | IOException | IllegalArgumentException e) {
                log.accept("Version déposée refusée (" + name + ") : " + e.getMessage());
                Path refused = Files.createDirectories(depot.resolve("refusees"));
                Files.move(manifestFile, refused.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                if (Files.exists(artifact)) {
                    Files.move(artifact, refused.resolve(artifact.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Télécharge la dernière version publiée ({@code <base>/latest.json}, puis {@code <base>/<artefact>}) si elle est
     * plus récente que celles connues. Une erreur n'est que signalée : la box démarre sur ce qu'elle a.
     */
    public void fetch(URI base, HttpClient http, long knownSequence) {
        String root = base.toString().replaceAll("/+$", "");
        try {
            HttpResponse<InputStream> latest = http.send(request(root + "/" + LATEST),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] latestBody;
            try (InputStream body = latest.body()) {
                if (latest.statusCode() != 200) {
                    throw new IOException("HTTP " + latest.statusCode() + " pour " + LATEST);
                }
                latestBody = body.readNBytes(MAX_MANIFEST + 1);
            }
            if (latestBody.length > MAX_MANIFEST) {
                throw new IOException(LATEST + " trop long");
            }
            SignedDocument document = SignedDocument.fromJson(new String(latestBody, StandardCharsets.UTF_8));
            ReleaseManifest manifest = verifier.verifyManifest(document);
            if (manifest.sequence() <= Math.max(knownSequence, highestInstalled())) {
                log.accept("Serveur de mises à jour : pas de version plus récente que n° "
                        + Math.max(knownSequence, highestInstalled()));
                return;
            }
            Path download = Files.createDirectories(dir.resolve("telechargement"))
                    .resolve(manifest.artifact().name());
            HttpResponse<InputStream> artifact = http.send(request(root + "/" + manifest.artifact().name()),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = artifact.body()) {
                if (artifact.statusCode() != 200) {
                    throw new IOException("HTTP " + artifact.statusCode() + " pour " + manifest.artifact().name());
                }
                // Pas plus que la taille signée dans le manifeste : un serveur compromis ne remplit pas le disque.
                long expected = manifest.artifact().size();
                long copied;
                try (java.io.OutputStream out = Files.newOutputStream(download)) {
                    copied = body.transferTo(new java.io.FilterOutputStream(out) {
                        private long written;

                        @Override
                        public void write(int b) throws IOException {
                            write(new byte[] {(byte) b}, 0, 1);
                        }

                        @Override
                        public void write(byte[] bytes, int offset, int length) throws IOException {
                            written += length;
                            if (written > expected) {
                                throw new IOException("Artefact plus long que la taille signée (" + expected
                                        + " octets) : téléchargement abandonné");
                            }
                            out.write(bytes, offset, length);
                        }
                    });
                }
                if (copied != expected) {
                    throw new IOException("Artefact tronqué : " + copied + " octets sur " + expected);
                }
                install(document, download);
            } finally {
                Files.deleteIfExists(download);
            }
            log.accept("Version " + manifest.label() + " téléchargée : installée");
        } catch (IOException | ReleaseException | DirectoryException | IllegalArgumentException e) {
            log.accept("Mise à jour par le serveur impossible : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.accept("Mise à jour par le serveur interrompue");
        }
    }

    /** Plus long manifeste lu. */
    static final int MAX_MANIFEST = 1024 * 1024;

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
    }

    /** Vérifie, puis installe d'un coup une version et son artefact (copié). */
    private void install(SignedDocument document, Path artifact) throws IOException {
        ReleaseManifest manifest = verifier.verify(document, artifact);
        Path versions = Files.createDirectories(dir.resolve("versions"));
        Path target = versions.resolve(Long.toString(manifest.sequence()));
        if (Files.exists(target)) {
            throw new ReleaseException("Version n° " + manifest.sequence() + " déjà installée");
        }
        // Dossier de préparation propre à cette installation : deux installations simultanées de la même version
        // (choix au démarrage et mise à jour de la nuit) ne se mélangent pas ; la première arrivée l'emporte.
        Path staging = Files.createTempDirectory(versions, ".installation-" + manifest.sequence() + "-");
        try {
            Files.copy(artifact, staging.resolve(manifest.artifact().name()));
            Files.writeString(staging.resolve("manifeste.json"), document.toJson(), StandardCharsets.UTF_8);
            // Revérifie la copie : c'est elle qui sera exécutée.
            verifier.verify(document, staging.resolve(manifest.artifact().name()));
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException | java.nio.file.DirectoryNotEmptyException e) {
                throw new ReleaseException("Version n° " + manifest.sequence() + " déjà installée");
            }
        } finally {
            deleteRecursively(staging);
        }
    }

    // --- Choix au démarrage ---

    /**
     * Choisit la version à exécuter : la plus récente des versions valides (version livrée avec l'image, versions
     * installées), enregistre son numéro, et supprime les versions installées plus anciennes.
     *
     * @param bundled artefact livré avec l'image ; son manifeste est à côté ({@code <artefact>.manifeste.json})
     * @throws ReleaseException si aucune version n'est valide, ou si la seule version valide est un retour en arrière
     */
    public Choice choose(Path bundled) throws IOException {
        List<Candidate> candidates = new ArrayList<>();
        Path bundledManifest = Path.of(bundled + MANIFEST_SUFFIX);
        if (Files.exists(bundledManifest)) {
            candidate(bundledManifest, bundled, "livrée avec l'image").ifPresent(candidates::add);
        } else {
            log.accept("Version livrée avec l'image non signée (" + bundledManifest.getFileName() + " absent)");
        }
        for (Path version : installedDirs()) {
            Optional<Candidate> c = readInstalled(version);
            if (c.isPresent()) {
                candidates.add(c.get());
            } else {
                // Version invalide (incomplète ou altérée) : supprimée, sinon elle bloquerait à jamais son
                // téléchargement (déjà « installée ») ; elle sera téléchargée de nouveau.
                deleteRecursively(version);
            }
        }
        Candidate best = candidates.stream().max(Comparator.comparingLong(c -> c.manifest().sequence()))
                .orElseThrow(() -> new ReleaseException("Aucune version signée valide : la box ne démarre pas"));
        Optional<Installed> installed = installed();
        if (installed.isPresent() && best.manifest().sequence() < installed.get().sequence()) {
            throw new ReleaseException("Retour en arrière refusé : la box a déjà exécuté la version "
                    + installed.get().version() + " (n° " + installed.get().sequence() + "), la plus récente des "
                    + "versions valides est " + best.manifest().label());
        }
        if (installed.isEmpty() || best.manifest().sequence() > installed.get().sequence()) {
            Files.createDirectories(dir);
            Path state = dir.resolve("etat.json");
            Path temporary = dir.resolve("etat.json.nouveau");
            Files.writeString(temporary, ReleaseJson.write(new Installed(best.manifest().sequence(),
                    best.manifest().version(), clock.instant())), StandardCharsets.UTF_8);
            Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.accept("Passage à la version " + best.manifest().label());
        }
        for (Path version : installedDirs()) {
            if (Long.parseLong(version.getFileName().toString()) < best.manifest().sequence()) {
                deleteRecursively(version);
            }
        }
        log.accept("Démarrage sur la version " + best.manifest().label() + ", " + best.origin() + ", signature vérifiée");
        return new Choice(best.manifest(), best.jar());
    }

    private Optional<Candidate> readInstalled(Path version) {
        Path manifestFile = version.resolve("manifeste.json");
        try {
            ReleaseManifest manifest = ReleaseManifest.parse(SignedDocument.fromJson(Files.readString(manifestFile,
                    StandardCharsets.UTF_8)));
            if (manifest.sequence() != Long.parseLong(version.getFileName().toString())) {
                throw new ReleaseException("Numéro différent de celui du dossier");
            }
            return candidate(manifestFile, version.resolve(manifest.artifact().name()), "installée");
        } catch (IOException | RuntimeException e) {
            log.accept("Version installée " + version.getFileName() + " écartée : " + e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Candidate> candidate(Path manifestFile, Path jar, String origin) {
        try {
            SignedDocument document = SignedDocument.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8));
            return Optional.of(new Candidate(verifier.verify(document, jar), jar, origin));
        } catch (IOException | RuntimeException e) {
            log.accept("Version " + origin + " écartée : " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Plus haut numéro parmi les versions installées (chacune vérifiée avant son installation), ou 0. */
    public long highestInstalled() throws IOException {
        return installedDirs().stream().mapToLong(p -> Long.parseLong(p.getFileName().toString())).max().orElse(0);
    }

    private List<Path> installedDirs() throws IOException {
        Path versions = dir.resolve("versions");
        if (!Files.isDirectory(versions)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(versions)) {
            return dirs.filter(Files::isDirectory).filter(p -> p.getFileName().toString().matches("[1-9][0-9]{0,17}"))
                    .sorted().toList();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
