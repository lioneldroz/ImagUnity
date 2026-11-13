package com.imagunity.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.SignedDocument;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MessagesTest {

    private static final HybridKemKeyPair B = HybridKemKeyPair.generate();
    private static final HybridKeyPair A_IDENTITY = HybridKeyPair.generate();
    private static final UUID A = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T17:00:00Z");
    private static final Function<UUID, Optional<HybridPublicKey>> DIRECTORY =
            id -> id.equals(A) ? Optional.of(A_IDENTITY.publicKey()) : Optional.empty();

    @Test
    void envelopesArePaddedToTiersAndOpenOnlyForTheRecipient() {
        byte[] small = "court".getBytes(StandardCharsets.UTF_8);
        byte[] medium = new byte[5000];
        byte[] large = new byte[MessageEnvelope.MAX_CONTENT];

        byte[] e1 = MessageEnvelope.seal(small, B.publicKey());
        byte[] e2 = MessageEnvelope.seal(new byte[100], B.publicKey());
        byte[] e3 = MessageEnvelope.seal(medium, B.publicKey());
        byte[] e4 = MessageEnvelope.seal(large, B.publicKey());

        assertThat(MessageEnvelope.open(e1, B)).isEqualTo(small);
        assertThat(MessageEnvelope.open(e4, B)).isEqualTo(large);
        assertThat(e1.length).as("même palier, même taille").isEqualTo(e2.length);
        assertThat(e3.length - e1.length).isEqualTo(12 * 1024);
        assertThat(e4.length - e1.length).isEqualTo(60 * 1024);
        assertThat(MessageEnvelope.tier(4096)).isEqualTo(4096);
        assertThatThrownBy(() -> MessageEnvelope.seal(new byte[MessageEnvelope.MAX_CONTENT + 1], B.publicKey()))
                .hasMessageContaining("trop long");
        assertThatThrownBy(() -> MessageEnvelope.tier(70_000)).hasMessageContaining("trop long");
        assertThatThrownBy(() -> MessageEnvelope.open(e1, HybridKemKeyPair.generate()))
                .hasMessageContaining("autre box");
    }

    @Test
    void alteredOrMalformedEnvelopesAreRefused() {
        byte[] envelope = MessageEnvelope.seal("contenu".getBytes(StandardCharsets.UTF_8), B.publicKey());
        int headerLength = ByteBuffer.wrap(envelope, 5, 2).getShort();
        String header = new String(envelope, 7, headerLength, StandardCharsets.UTF_8);

        byte[] flipped = envelope.clone();
        flipped[flipped.length - 1] ^= 1;
        assertThatThrownBy(() -> MessageEnvelope.open(flipped, B)).hasMessageContaining("altéré");
        assertThatThrownBy(() -> MessageEnvelope.open("IU1X....".getBytes(), B)).hasMessageContaining("non reconnue");
        assertThatThrownBy(() -> MessageEnvelope.open(new byte[3], B)).hasMessageContaining("non reconnue");
        byte[] version = envelope.clone();
        version[4] = 7;
        assertThatThrownBy(() -> MessageEnvelope.open(version, B)).hasMessageContaining("Version");
        byte[] shortHeader = envelope.clone();
        ByteBuffer.wrap(shortHeader, 5, 2).putShort((short) 0);
        assertThatThrownBy(() -> MessageEnvelope.open(shortHeader, B)).hasMessageContaining("En-tête");
        assertThatThrownBy(() -> MessageEnvelope.open(Arrays.copyOf(envelope, 20), B)).hasMessageContaining("En-tête");
        assertThatThrownBy(() -> MessageEnvelope.open(rebuild(envelope, header.replace("\"IU1\"", "\"IU2\"")), B))
                .hasMessageContaining("IU2");
        assertThatThrownBy(() -> MessageEnvelope.open(rebuild(envelope,
                header.replaceFirst("\"x25519\":\"[^\"]+\"", "\"x25519\":\"AAAA\"")), B)).hasMessageContaining("invalide");
        assertThatThrownBy(() -> MessageEnvelope.open(rebuild(envelope,
                header.replaceFirst("\"mlKem768\":\"[^\"]+\"", "\"mlKem768\":\"%%\"")), B)).hasMessageContaining("invalide");
        assertThatThrownBy(() -> MessageEnvelope.open(rebuild(envelope, "{\"inconnu\":1}"), B))
                .hasMessageContaining("illisible");
        assertThatThrownBy(() -> MessageEnvelope.open(rebuild(envelope, header.replace("\"x25519\"", "\"x25519bis\"")
                .replaceFirst(",\"x25519bis\":\"[^\"]+\"", "")), B)).hasMessageContaining("invalide");
    }

    private static byte[] rebuild(byte[] envelope, String header) {
        int headerLength = ByteBuffer.wrap(envelope, 5, 2).getShort();
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        int rest = envelope.length - 7 - headerLength;
        return ByteBuffer.allocate(7 + h.length + rest).put(envelope, 0, 5).putShort((short) h.length).put(h)
                .put(envelope, 7 + headerLength, rest).array();
    }

    /** Box destinataire des essais (ses clés de chiffrement sont {@code B}). */
    private static final UUID B_ID = UUID.randomUUID();

    @Test
    void signedMessagesAreVerifiedAgainstTheDirectory() {
        SignedMessage message = SignedMessage.of("file.ack", A, B_ID, "{\"x\":1}", NOW);
        byte[] sealed = message.seal(A_IDENTITY, B.publicKey());

        assertThat(SignedMessage.open(sealed, B, DIRECTORY, B_ID, NOW)).isEqualTo(message);
        assertThatThrownBy(() -> SignedMessage.open(sealed, B, id -> Optional.empty(), B_ID, NOW))
                .isInstanceOf(SignedMessage.UnknownSender.class).hasMessageContaining("absente de l'annuaire");
        assertThatThrownBy(() -> SignedMessage.open(sealed, B, id -> Optional.of(HybridKeyPair.generate().publicKey()),
                B_ID, NOW)).hasMessageContaining("Signature du message invalide");
        // Message signé par une autre box qui se fait passer pour A.
        byte[] impostor = message.seal(HybridKeyPair.generate(), B.publicKey());
        assertThatThrownBy(() -> SignedMessage.open(impostor, B, DIRECTORY, B_ID, NOW)).hasMessageContaining("invalide");
        byte[] notSigned = MessageEnvelope.seal("pas un document".getBytes(StandardCharsets.UTF_8), B.publicKey());
        assertThatThrownBy(() -> SignedMessage.open(notSigned, B, DIRECTORY, B_ID, NOW))
                .hasMessageContaining("illisible");
        byte[] otherPayload = MessageEnvelope.seal(SignedDocument.sign("{\"a\":1}", A_IDENTITY).toJson()
                .getBytes(StandardCharsets.UTF_8), B.publicKey());
        assertThatThrownBy(() -> SignedMessage.open(otherPayload, B, DIRECTORY, B_ID, NOW))
                .hasMessageContaining("illisible");
    }

    @Test
    void aMessageForAnotherBoxOrOutsideItsTimeWindowIsRefused() {
        // Une box C fait suivre à B un message que A lui avait adressé (rechiffré pour B) : refusé.
        UUID c = UUID.randomUUID();
        byte[] forwarded = SignedMessage.of("file.announce", A, c, "{}", NOW).seal(A_IDENTITY, B.publicKey());
        assertThatThrownBy(() -> SignedMessage.open(forwarded, B, DIRECTORY, B_ID, NOW))
                .hasMessageContaining("pour une autre box");
        byte[] old = SignedMessage.of("ping", A, B_ID, "", NOW).seal(A_IDENTITY, B.publicKey());
        assertThatThrownBy(() -> SignedMessage.open(old, B, DIRECTORY, B_ID, NOW.plus(SignedMessage.MAX_AGE)
                .plusSeconds(1))).hasMessageContaining("trop ancien");
        assertThatThrownBy(() -> SignedMessage.open(old, B, DIRECTORY, B_ID, NOW.minus(SignedMessage.CLOCK_SKEW)
                .minusSeconds(1))).hasMessageContaining("futur");
    }

    @Test
    void messageFieldsAreValidated() {
        String id = "AAECAwQFBgcICQoLDA0ODw";
        assertThat(SignedMessage.of("ping", A, B_ID, "", NOW).messageId()).hasSize(22)
                .isNotEqualTo(SignedMessage.of("ping", A, B_ID, "", NOW).messageId());
        assertThatThrownBy(() -> new SignedMessage("IU2", id, NOW, "ping", A, B_ID, "")).hasMessageContaining("Suite");
        assertThatThrownBy(() -> new SignedMessage("IU1", "x", NOW, "ping", A, B_ID, ""))
                .hasMessageContaining("Identifiant");
        assertThatThrownBy(() -> new SignedMessage("IU1", null, NOW, "ping", A, B_ID, ""))
                .hasMessageContaining("Identifiant");
        assertThatThrownBy(() -> new SignedMessage("IU1", id, NOW, "Ping!", A, B_ID, "")).hasMessageContaining("Type");
        assertThatThrownBy(() -> new SignedMessage("IU1", id, NOW, null, A, B_ID, "")).hasMessageContaining("Type");
        assertThatThrownBy(() -> new SignedMessage("IU1", id, null, "ping", A, B_ID, ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SignedMessage("IU1", id, NOW, "ping", A, null, ""))
                .hasMessageContaining("destinataire");
    }
}
