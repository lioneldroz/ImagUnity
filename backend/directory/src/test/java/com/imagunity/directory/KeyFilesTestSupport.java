package com.imagunity.directory;

import java.io.IOException;
import java.nio.file.Path;

/** Outils de test des fichiers de clés. */
final class KeyFilesTestSupport {

    private KeyFilesTestSupport() {
    }

    /** Clé en clair (format antérieur à la protection par phrase de passe). */
    static Path clearKey(Path dir) throws IOException {
        Path file = dir.resolve("clair-" + System.nanoTime() + ".json");
        KeyFiles.writePrivate(file, HybridKeyPair.generate());
        return file;
    }
}
