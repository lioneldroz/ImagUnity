package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mémoire des messages déjà traités, gardée sur disque (anti-rejeu après redémarrage). */
class IuStoreSeenTest {

    @TempDir
    Path dir;

    @Test
    void seenMessagesSurviveAndAreForgottenOnceOld() throws Exception {
        IuStore store = new IuStore(dir);
        assertThat(store.seenMessages()).isEmpty();
        Instant old = Instant.parse("2026-11-01T10:00:00Z");
        Instant recent = Instant.parse("2026-11-13T10:00:00Z");
        store.markSeen("ancien", old);
        store.markSeen("recent", recent);
        Files.writeString(dir.resolve("messages-vus.txt"), "abîmé\ndate-invalide pas-une-date\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertThat(new IuStore(dir).seenMessages()).containsOnlyKeys("ancien", "recent");
        store.forgetSeenBefore(Instant.parse("2026-11-05T00:00:00Z"));
        assertThat(new IuStore(dir).seenMessages()).containsOnlyKeys("recent");
    }

    @Test
    void aDamagedStateFileIsSetAsideInsteadOfBlockingTheWholeDirectory() throws Exception {
        IuStore store = new IuStore(dir);
        java.util.UUID to = java.util.UUID.randomUUID();
        Instant now = Instant.parse("2026-11-13T10:00:00Z");
        store.addPending(to, new byte[] {1}, now);
        store.addPending(to, new byte[] {2}, now);
        Path damaged;
        try (var files = Files.list(dir.resolve("sortants"))) {
            damaged = files.sorted().toList().getFirst();
        }
        Files.writeString(damaged, ""); // coupure de courant : fichier vide sous son nom définitif

        assertThat(store.pending()).as("l'autre message reste déposable").singleElement()
                .satisfies(p -> assertThat(p.envelope()).isEqualTo(com.imagunity.protocol.Codec.base64Url(
                        new byte[] {2})));
        assertThat(damaged).doesNotExist();
        assertThat(damaged.resolveSibling(damaged.getFileName() + ".illisible")).exists();
        assertThat(store.pending()).hasSize(1);
    }
}
