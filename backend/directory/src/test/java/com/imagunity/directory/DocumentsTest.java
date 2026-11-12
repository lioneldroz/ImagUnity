package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentsTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private static final HybridKeyPair BOX_KEYS = HybridKeyPair.generate();
    private static final HybridKemKeyPair BOX_ENCRYPTION = HybridKemKeyPair.generate();
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final UUID BOX = UUID.fromString("3f6c0d2e-8a8b-4b6e-9a51-0f6d3c2b1a00");

    private final Organization organization = new Organization("chu-exemple", ORG_KEYS);

    private SignedDocument request() {
        return EnrollmentRequest.create(BOX_KEYS, BOX, "CHU Exemple — imagerie", Map.of("simplex", "simplex:/contact#x"),
                NOW);
    }

    private EntryVerifier verifier(Instant at) {
        return new EntryVerifier("chu-exemple", ORG_KEYS.publicKey(), SerialMemory.inMemory(),
                Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    void enrollmentRequestProvesPossessionOfTheBoxKeys() {
        EnrollmentRequest request = EnrollmentRequest.verify(request());

        assertThat(request.boxId()).isEqualTo(BOX);
        assertThat(request.transport()).containsEntry("simplex", "simplex:/contact#x");
        assertThat(HybridPublicKey.decode(request.signingKeys()).fingerprint())
                .isEqualTo(BOX_KEYS.publicKey().fingerprint());
    }

    @Test
    void forgedOrTamperedRequestsAreRefused() {
        SignedDocument genuine = request();
        SignedDocument tampered = new SignedDocument(genuine.payload().replace("imagerie", "radiologie"),
                genuine.signatures());
        // Demande qui présente les clés de la box mais signée par quelqu'un d'autre.
        SignedDocument impostor = SignedDocument.sign(genuine.payload(), HybridKeyPair.generate());
        SignedDocument otherSuite = SignedDocument.sign(genuine.payload().replace("\"IU1\"", "\"IU2\""), BOX_KEYS);

        assertThatThrownBy(() -> EnrollmentRequest.verify(tampered)).isInstanceOf(DirectoryException.class);
        assertThatThrownBy(() -> EnrollmentRequest.verify(impostor)).isInstanceOf(DirectoryException.class)
                .hasMessageContaining("non signée par les clés");
        assertThatThrownBy(() -> EnrollmentRequest.verify(otherSuite)).hasMessageContaining("IU2");
        assertThatThrownBy(() -> EnrollmentRequest.verify(SignedDocument.sign("{\"inconnu\":1}", BOX_KEYS)))
                .isInstanceOf(DirectoryException.class).hasMessageContaining("illisible");
    }

    @Test
    void encryptionKeysTravelFromTheRequestToTheEntryAndSurviveRevocation() {
        SignedDocument request = EnrollmentRequest.create(BOX_KEYS, BOX_ENCRYPTION.publicKey(), BOX, "CHU Exemple",
                Map.of(), NOW);
        SignedDocument entry = organization.enroll(request, NOW, Duration.ofDays(365), 1);
        BoxEntry e = verifier(NOW.plusSeconds(60)).verify(entry).entry();

        assertThat(e.encryptionKey()).get().extracting(HybridKemPublicKey::fingerprint)
                .isEqualTo(BOX_ENCRYPTION.publicKey().fingerprint());
        BoxEntry revoked = verifier(NOW.plusSeconds(120))
                .verify(organization.revoke(entry, NOW.plusSeconds(90), "Box volée")).entry();
        assertThat(revoked.encryptionKeys()).isEqualTo(e.encryptionKeys());

        // Sans clés de chiffrement, le champ est absent du document : les box antérieures le lisent encore.
        SignedDocument plain = organization.enroll(request(), NOW, Duration.ofDays(365), 1);
        assertThat(plain.payload()).doesNotContain("encryptionKeys");
        assertThat(verifier(NOW.plusSeconds(60)).verify(plain).entry().encryptionKey()).isEmpty();

        SignedDocument unreadable = SignedDocument.sign(request.payload().replaceFirst(
                "\"x25519\":\"[^\"]+\"", "\"x25519\":\"AAAA\""), BOX_KEYS);
        assertThatThrownBy(() -> EnrollmentRequest.verify(unreadable)).hasMessageContaining("illisible");
        assertThat(new EnrollmentRequest(Suite.ID, BOX, "B", BOX_KEYS.publicKey().encoded(), null, NOW)
                .encryptionKeys()).isNull();
    }

    @Test
    void enrolledEntryIsActiveDuringItsValidity() {
        SignedDocument entry = organization.enroll(request(), NOW, Duration.ofDays(365), 1);

        EntryVerifier.Verified verified = verifier(NOW.plusSeconds(60)).verify(entry);

        assertThat(verified.status()).isEqualTo(BoxEntry.Status.ACTIVE);
        assertThat(verified.usable()).isTrue();
        BoxEntry e = verified.entry();
        assertThat(e.organization()).isEqualTo("chu-exemple");
        assertThat(e.displayName()).isEqualTo("CHU Exemple — imagerie");
        assertThat(e.serial()).isEqualTo(1);
        assertThat(e.validUntil()).isEqualTo(NOW.plus(Duration.ofDays(365)));
        assertThat(e.signingKey().fingerprint()).isEqualTo(BOX_KEYS.publicKey().fingerprint());
        assertThat(verifier(NOW.minusSeconds(1)).verify(entry).status()).isEqualTo(BoxEntry.Status.NOT_YET_VALID);
        EntryVerifier.Verified expired = verifier(NOW.plus(Duration.ofDays(365))).verify(entry);
        assertThat(expired.status()).isEqualTo(BoxEntry.Status.EXPIRED);
        assertThat(expired.usable()).isFalse();
    }

    @Test
    void revocationSupersedesTheEntryAndCannotBeRolledBack() {
        SignedDocument entry = organization.enroll(request(), NOW, Duration.ofDays(365), 1);
        SignedDocument revoked = organization.revoke(entry, NOW.plusSeconds(3600), "Box volée");
        EntryVerifier verifier = verifier(NOW.plusSeconds(7200));

        EntryVerifier.Verified v = verifier.verify(revoked);

        assertThat(v.status()).isEqualTo(BoxEntry.Status.REVOKED);
        assertThat(v.entry().serial()).isEqualTo(2);
        assertThat(v.entry().revocationReason()).isEqualTo("Box volée");
        assertThat(v.entry().revokedAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(verifier.verify(revoked).status()).as("même entrée revue").isEqualTo(BoxEntry.Status.REVOKED);
        assertThatThrownBy(() -> verifier.verify(entry)).isInstanceOf(DirectoryException.class)
                .hasMessageContaining("Retour en arrière");
        assertThatThrownBy(() -> organization.revoke(revoked, NOW, "encore")).hasMessageContaining("déjà révoquée");
        assertThatThrownBy(() -> new Organization("autre", HybridKeyPair.generate()).revoke(entry, NOW, "x"))
                .hasMessageContaining("non signée par cette organisation");
    }

    @Test
    void entriesOfAnotherOrganizationOrSignerAreRefused() {
        SignedDocument entry = organization.enroll(request(), NOW, Duration.ofDays(30), 1);
        SignedDocument foreign = new Organization("chu-voisin", ORG_KEYS).enroll(request(), NOW, Duration.ofDays(30), 1);
        SignedDocument forged = new Organization("chu-exemple", HybridKeyPair.generate())
                .enroll(request(), NOW, Duration.ofDays(30), 1);
        SignedDocument otherSuite = SignedDocument.sign(entry.payload().replace("\"IU1\"", "\"IU9\""), ORG_KEYS);

        assertThatThrownBy(() -> verifier(NOW).verify(foreign)).hasMessageContaining("autre organisation");
        assertThatThrownBy(() -> verifier(NOW).verify(forged)).hasMessageContaining("non signée par le responsable");
        assertThatThrownBy(() -> verifier(NOW).verify(otherSuite)).hasMessageContaining("IU9");
    }

    @Test
    void signedDocumentsRoundTripThroughJson() {
        SignedDocument entry = organization.enroll(request(), NOW, Duration.ofDays(30), 1);

        SignedDocument back = SignedDocument.fromJson(entry.toJson());

        assertThat(back).isEqualTo(entry);
        assertThat(back.isSignedBy(ORG_KEYS.publicKey())).isTrue();
        assertThatThrownBy(() -> SignedDocument.fromJson("null")).isInstanceOf(DirectoryException.class);
        assertThatThrownBy(() -> SignedDocument.fromJson("{\"payload\":\"x\"}")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SignedDocument.fromJson("pas du json")).isInstanceOf(DirectoryException.class);
    }

    @Test
    void invariants() {
        HybridPublicKey.Encoded keys = BOX_KEYS.publicKey().encoded();
        assertThatThrownBy(() -> new Organization("Majuscules", ORG_KEYS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Organization(null, ORG_KEYS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoxEntry("IU1", "o", BOX, "n", keys, null, 0, NOW, NOW.plusSeconds(1), false,
                null, null)).hasMessageContaining("commence à 1");
        assertThatThrownBy(() -> new BoxEntry("IU1", "o", BOX, "n", keys, null, 1, NOW, NOW, false, null, null))
                .hasMessageContaining("Fin de validité");
        assertThat(new BoxEntry("IU1", "o", BOX, "n", keys, null, 1, NOW, NOW.plusSeconds(1), false, null, null)
                .transport()).isEmpty();
        assertThat(new EnrollmentRequest("IU1", BOX, "n", keys, null, NOW).transport()).isEmpty();
        assertThat(organization.id()).isEqualTo("chu-exemple");
        SerialMemory memory = SerialMemory.inMemory();
        memory.record(BOX, 3);
        memory.record(BOX, 2);
        assertThat(memory.highest(BOX)).hasValue(3);
        assertThat(memory.highest(UUID.randomUUID())).isEmpty();
        assertThatThrownBy(() -> Suite.require(null)).isInstanceOf(DirectoryException.class);
        assertThat(new DirectoryException("x", new RuntimeException()).getCause()).isNotNull();
    }
}
