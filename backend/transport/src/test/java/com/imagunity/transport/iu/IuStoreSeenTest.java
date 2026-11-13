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
}
