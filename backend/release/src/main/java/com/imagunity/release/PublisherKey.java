package com.imagunity.release;

import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.Suite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Clé publique de l'éditeur, installée dans chaque box et épinglée par son empreinte courte, vérifiée hors bande.
 *
 * @param suite       {@code IU1}
 * @param publisher   nom de l'éditeur
 * @param signingKeys clé publique de signature des versions
 * @param shortCode   empreinte courte (rappel ; recalculée à la lecture)
 */
public record PublisherKey(String suite, String publisher, HybridPublicKey.Encoded signingKeys, String shortCode) {

    public PublisherKey {
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(signingKeys, "signingKeys");
    }

    public HybridPublicKey key() {
        return HybridPublicKey.decode(signingKeys);
    }

    public static void write(Path file, String publisher, HybridPublicKey key) throws IOException {
        Files.writeString(file, ReleaseJson.write(new PublisherKey(Suite.ID, publisher, key.encoded(),
                key.shortCode())), StandardCharsets.UTF_8);
    }

    public static PublisherKey read(Path file) throws IOException {
        PublisherKey key = ReleaseJson.read(Files.readString(file, StandardCharsets.UTF_8), PublisherKey.class,
                "Clé publique de l'éditeur");
        if (!Suite.ID.equals(key.suite())) {
            throw new ReleaseException("Suite cryptographique non prise en charge : " + key.suite());
        }
        return key;
    }
}
