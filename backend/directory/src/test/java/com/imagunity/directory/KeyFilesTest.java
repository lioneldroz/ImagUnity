package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyFilesTest {

    @TempDir
    Path dir;

    @Test
    void privateKeyRoundTripsAndIsReadableByItsOwnerOnly() throws IOException {
        HybridKeyPair keys = HybridKeyPair.generate();
        Path file = dir.resolve("sous-dossier/cle.json");

        KeyFiles.writePrivate(file, keys);
        HybridKeyPair back = KeyFiles.readPrivate(file);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(back.publicKey().fingerprint()).isEqualTo(keys.publicKey().fingerprint());
        byte[] data = {1, 2, 3};
        assertThat(HybridKeyPair.verify(keys.publicKey(), data, back.sign(data))).isTrue();
    }

    @Test
    void aKeyIsNeverOverwritten() throws IOException {
        Path file = dir.resolve("cle.json");
        KeyFiles.writePrivate(file, HybridKeyPair.generate());

        assertThatThrownBy(() -> KeyFiles.writePrivate(file, HybridKeyPair.generate()))
                .isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void corruptedKeyFilesAreRefused() throws IOException {
        HybridKeyPair keys = HybridKeyPair.generate();
        Path file = dir.resolve("cle.json");
        KeyFiles.writePrivate(file, keys);
        String content = Files.readString(file);
        Path broken = Files.writeString(dir.resolve("abimee.json"),
                content.replaceFirst("\"ed25519Private\":\"[^\"]+\"", "\"ed25519Private\":\"AAAA\""));
        Path otherSuite = Files.writeString(dir.resolve("autre.json"), content.replace("\"IU1\"", "\"IU2\""));

        assertThatThrownBy(() -> KeyFiles.readPrivate(broken)).isInstanceOf(DirectoryException.class)
                .hasMessageContaining("privée Ed25519");
        assertThatThrownBy(() -> KeyFiles.readPrivate(otherSuite)).isInstanceOf(DirectoryException.class);
    }

    @Test
    void organizationPublicKeyRoundTrips() throws IOException {
        HybridKeyPair keys = HybridKeyPair.generate();
        Path file = dir.resolve("org.pub.json");

        KeyFiles.writeOrganizationPublic(file, "chu-exemple", keys.publicKey());
        KeyFiles.OrganizationPublicKey back = KeyFiles.readOrganizationPublic(file);

        assertThat(back.organization()).isEqualTo("chu-exemple");
        assertThat(back.shortCode()).isEqualTo(keys.publicKey().shortCode());
        assertThat(back.key().fingerprint()).isEqualTo(keys.publicKey().fingerprint());
        Files.writeString(file, Files.readString(file).replace("chu-exemple", "CHU"));
        assertThatThrownBy(() -> KeyFiles.readOrganizationPublic(file)).isInstanceOf(IllegalArgumentException.class);
    }
}
