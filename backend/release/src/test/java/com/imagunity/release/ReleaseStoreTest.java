package com.imagunity.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.SignedDocument;
import com.imagunity.directory.Suite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseStoreTest {

    private static final HybridKeyPair PUBLISHER = HybridKeyPair.generate();
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @TempDir
    Path dir;

    private final List<String> messages = new ArrayList<>();
    private final ReleaseStore store = new ReleaseStore(Path.of("x"), null, null, null);

    private ReleaseStore store() {
        return new ReleaseStore(dir.resolve("maj"), new ReleaseVerifier(PUBLISHER.publicKey(), ReleaseManifest.BOX),
                Clock.fixed(NOW, ZoneOffset.UTC), messages::add);
    }

    private static SignedDocument manifest(Path jar, String version, long sequence) throws IOException {
        return new ReleaseManifest(Suite.ID, ReleaseManifest.BOX, version, sequence,
                new ReleaseManifest.Artifact(jar.getFileName().toString(), Files.size(jar),
                        ReleaseVerifier.sha256(jar)), NOW).sign(PUBLISHER);
    }

    /** Version installée à la main dans {@code versions/<dossier>}. */
    private Path installed(String folder, String version, long sequence) throws IOException {
        Path target = Files.createDirectories(dir.resolve("maj/versions/" + folder));
        Path jar = Files.writeString(target.resolve("box.jar"), "logiciel " + version);
        Files.writeString(target.resolve("manifeste.json"), manifest(jar, version, sequence).toJson());
        return jar;
    }

    @Test
    void olderVersionsArePrunedAndInvalidFoldersIgnored() throws IOException {
        installed("1", "0.1.0", 1);
        Path latest = installed("3", "0.3.0", 3);
        installed("7", "0.7.0", 8); // dossier qui ne correspond pas au numéro signé
        Files.createDirectories(dir.resolve("maj/versions/pas-un-numero"));
        Files.writeString(Files.createDirectories(dir.resolve("maj/versions/9")).resolve("manifeste.json"), "{}");

        ReleaseStore.Choice choice = store().choose(dir.resolve("absent.jar"));

        assertThat(choice.jar()).isEqualTo(latest);
        assertThat(choice.manifest().label()).isEqualTo("0.3.0 (n° 3)");
        assertThat(dir.resolve("maj/versions/1")).doesNotExist();
        assertThat(messages).anyMatch(m -> m.contains("7 écartée") && m.contains("Numéro différent"))
                .anyMatch(m -> m.contains("9 écartée"));
        assertThat(store().installed()).get().extracting(ReleaseStore.Installed::sequence).isEqualTo(3L);

        // Même version au redémarrage : état inchangé.
        messages.clear();
        store().choose(dir.resolve("absent.jar"));
        assertThat(messages).noneMatch(m -> m.startsWith("Passage"));
    }

    @Test
    void depositedPiecesAreChecked() throws IOException {
        Path depot = Files.createDirectories(dir.resolve("maj/depot"));
        Path jar = Files.writeString(depot.resolve("box.jar"), "logiciel");
        Path other = Files.writeString(dir.resolve("autre.jar"), "logiciel");
        Files.writeString(depot.resolve("box.jar" + ReleaseStore.MANIFEST_SUFFIX),
                manifest(other, "0.2.0", 2).toJson());
        Files.writeString(depot.resolve("orphelin.jar" + ReleaseStore.MANIFEST_SUFFIX), "pas du json");
        Files.writeString(depot.resolve("notes.txt"), "ignoré");

        store().importDepot();

        assertThat(messages).anyMatch(m -> m.contains("autre artefact")).anyMatch(m -> m.contains("orphelin.jar"));
        assertThat(depot.resolve("refusees/box.jar")).exists();
        assertThat(depot.resolve("notes.txt")).exists();
        assertThat(jar).doesNotExist();

        // Version déjà installée, déposée à nouveau.
        installed("2", "0.2.0", 2);
        Path again = Files.writeString(depot.resolve("encore.jar"), "logiciel 0.2.0");
        Files.writeString(depot.resolve("encore.jar" + ReleaseStore.MANIFEST_SUFFIX),
                manifest(again, "0.2.0", 2).toJson());
        messages.clear();
        store().importDepot();
        assertThat(messages).anyMatch(m -> m.contains("déjà installée"));

        new ReleaseStore(dir.resolve("vide"), null, null, messages::add).importDepot();
    }

    @Test
    void manifestsAreValidated() throws IOException {
        Path jar = Files.writeString(dir.resolve("box.jar"), "x");
        String sha = ReleaseVerifier.sha256(jar);
        ReleaseManifest.Artifact artifact = new ReleaseManifest.Artifact("box.jar", 1, sha);

        assertThatThrownBy(() -> new ReleaseManifest(Suite.ID, "p", "1.0 bis", 1, artifact, NOW))
                .hasMessageContaining("Version invalide");
        assertThatThrownBy(() -> new ReleaseManifest(Suite.ID, "p", null, 1, artifact, NOW))
                .hasMessageContaining("Version invalide");
        assertThatThrownBy(() -> new ReleaseManifest(Suite.ID, "p", "1", 0, artifact, NOW))
                .hasMessageContaining("commence à 1");
        assertThatThrownBy(() -> new ReleaseManifest.Artifact("../box.jar", 1, sha)).hasMessageContaining("Nom");
        assertThatThrownBy(() -> new ReleaseManifest.Artifact("a..b", 1, sha)).hasMessageContaining("Nom");
        assertThatThrownBy(() -> new ReleaseManifest.Artifact(null, 1, sha)).hasMessageContaining("Nom");
        assertThatThrownBy(() -> new ReleaseManifest.Artifact("box.jar", 0, sha)).hasMessageContaining("vide");
        assertThatThrownBy(() -> new ReleaseManifest.Artifact("box.jar", 1, "abc")).hasMessageContaining("Empreinte");
        assertThatThrownBy(() -> new ReleaseManifest.Artifact("box.jar", 1, null)).hasMessageContaining("Empreinte");

        ReleaseVerifier verifier = new ReleaseVerifier(PUBLISHER.publicKey(), ReleaseManifest.BOX);
        SignedDocument otherSuite = SignedDocument.sign(manifest(jar, "1", 1).payload().replace("\"IU1\"", "\"IU2\""),
                PUBLISHER);
        assertThatThrownBy(() -> verifier.verifyManifest(otherSuite)).hasMessageContaining("IU2");
        SignedDocument unknown = SignedDocument.sign("{\"inconnu\":1}", PUBLISHER);
        assertThatThrownBy(() -> verifier.verifyManifest(unknown)).isInstanceOf(ReleaseException.class)
                .hasMessageContaining("illisible");
        assertThatThrownBy(() -> verifier.verifyManifest(SignedDocument.sign("null", PUBLISHER)))
                .hasMessageContaining("vide");

        Path keyFile = dir.resolve("cle.pub.json");
        PublisherKey.write(keyFile, "ImagUnity", PUBLISHER.publicKey());
        assertThat(PublisherKey.read(keyFile).key().fingerprint()).isEqualTo(PUBLISHER.publicKey().fingerprint());
        Files.writeString(keyFile, Files.readString(keyFile, StandardCharsets.UTF_8).replace("\"IU1\"", "\"IU2\""));
        assertThatThrownBy(() -> PublisherKey.read(keyFile)).hasMessageContaining("IU2");
        assertThat(new ReleaseException("x", new IOException()).getCause()).isInstanceOf(IOException.class);
        assertThat(ReleaseTool.normalize(null)).isEmpty();
        assertThat(store).isNotNull();
    }
}
