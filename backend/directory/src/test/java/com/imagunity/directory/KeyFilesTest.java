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

    private static final char[] PHRASE = "une phrase de passe solide".toCharArray();

    @Test
    void protectedKeyRoundTripsWithItsPassphraseOnly() throws IOException {
        HybridKeyPair keys = HybridKeyPair.generate();
        Path file = dir.resolve("cle.json");

        KeyFiles.writePrivate(file, keys, PHRASE);

        assertThat(KeyFiles.isProtected(file)).isTrue();
        assertThat(Files.readString(file)).doesNotContain("Private\":\"M").contains("PBKDF2-HMAC-SHA256");
        assertThat(KeyFiles.readPrivate(file, PHRASE).publicKey().fingerprint()).isEqualTo(keys.publicKey().fingerprint());
        assertThatThrownBy(() -> KeyFiles.readPrivate(file)).isInstanceOf(DirectoryException.class)
                .hasMessageContaining("phrase de passe manquante");
        assertThatThrownBy(() -> KeyFiles.readPrivate(file, "une autre phrase de passe".toCharArray()))
                .isInstanceOf(DirectoryException.class).hasMessageContaining("incorrecte");
        assertThatThrownBy(() -> KeyFiles.writePrivate(dir.resolve("courte.json"), keys, "court".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(KeyFiles.readPrivate(KeyFilesTestSupport.clearKey(dir), PHRASE)).as("clé en clair : phrase ignorée")
                .isNotNull();
    }

    @Test
    void tamperedOrTransplantedProtectedKeysAreRefused() throws IOException {
        Path a = dir.resolve("a.json");
        Path b = dir.resolve("b.json");
        KeyFiles.writePrivate(a, HybridKeyPair.generate(), PHRASE);
        KeyFiles.writePrivate(b, HybridKeyPair.generate(), PHRASE);
        String contentA = Files.readString(a);
        String contentB = Files.readString(b);
        // Chiffré de A sous la clé publique de B : lié à la clé publique, il ne s'ouvre pas.
        String protectionA = contentA.substring(contentA.indexOf("\"protection\""));
        String transplanted = contentB.substring(0, contentB.indexOf("\"protection\"")) + protectionA;
        Path swapped = Files.writeString(dir.resolve("echange.json"), transplanted);
        String ciphertext = contentA.replaceAll(".*\"ciphertext\":\"([^\"]+)\".*", "$1");
        Path tampered = Files.writeString(dir.resolve("altere.json"), contentA.replace(ciphertext,
                (ciphertext.charAt(0) == 'A' ? "B" : "A") + ciphertext.substring(1)));
        Path weak = Files.writeString(dir.resolve("faible.json"), contentA.replace("\"iterations\":600000",
                "\"iterations\":1000"));
        Path garbage = Files.writeString(dir.resolve("illisible.json"), contentA.replace(ciphertext, "!!!"));

        assertThatThrownBy(() -> KeyFiles.readPrivate(swapped, PHRASE)).hasMessageContaining("incorrecte");
        assertThatThrownBy(() -> KeyFiles.readPrivate(tampered, PHRASE)).hasMessageContaining("altéré");
        assertThatThrownBy(() -> KeyFiles.readPrivate(weak, PHRASE)).hasMessageContaining("non prise en charge");
        assertThatThrownBy(() -> KeyFiles.readPrivate(garbage, PHRASE)).hasMessageContaining("illisible");
    }

    @Test
    void clearKeyIsProtectedInPlace() throws IOException {
        Path file = KeyFilesTestSupport.clearKey(dir);
        String fingerprint = KeyFiles.readPrivate(file).publicKey().fingerprint();

        KeyFiles.protect(file, PHRASE);

        assertThat(KeyFiles.isProtected(file)).isTrue();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(KeyFiles.readPrivate(file, PHRASE).publicKey().fingerprint()).isEqualTo(fingerprint);
        assertThat(Files.exists(file.resolveSibling(file.getFileName() + ".nouveau"))).isFalse();
        assertThatThrownBy(() -> KeyFiles.protect(file, PHRASE)).hasMessageContaining("déjà protégée");
    }
}
