package com.imagunity.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridPublicKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vecteurs de test figés de la suite IU1 ({@code vecteurs-iu1.json}) : la box et le relais doivent produire et accepter
 * exactement ces octets. Les valeurs déterministes (chaîne canonique, signature Ed25519, empreintes) sont recalculées ;
 * les pièces aléatoires (signature ML-DSA, encapsulations) sont vérifiées ou ouvertes avec les clés fournies.
 */
class VectorsTest {

    @TempDir
    Path dir;

    private static JsonNode vectors() throws IOException {
        try (InputStream in = VectorsTest.class.getResourceAsStream("/vecteurs-iu1.json")) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    private static byte[] b64(JsonNode node) {
        return Base64.getDecoder().decode(node.asString());
    }

    @Test
    void capabilityRequestIsReproducedExactly() throws Exception {
        JsonNode v = vectors().get("capabilityRequest");
        SignedRequest request = SignedRequest.received(v.get("method").asString(), v.get("path").asString(),
                v.get("timestamp").asString(), v.get("requestId").asString(),
                v.get("body").asString().getBytes(StandardCharsets.UTF_8));
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                b64(v.get("ed25519Pkcs8"))));

        assertThat(new String(request.canonical(), StandardCharsets.UTF_8)).isEqualTo(v.get("canonical").asString());
        assertThat(request.signCapability(key)).as("Ed25519 est déterministe").isEqualTo(v.get("signature").asString());
    }

    @Test
    void depositRequestSignatureVerifies() throws Exception {
        JsonNode v = vectors().get("depositRequest");
        SignedRequest request = SignedRequest.received(v.get("method").asString(), v.get("path").asString(),
                v.get("timestamp").asString(), v.get("requestId").asString(),
                v.get("body").asString().getBytes(StandardCharsets.UTF_8));
        HybridPublicKey key = HybridPublicKey.decode(new HybridPublicKey.Encoded(
                v.get("signingKeys").get("ed25519").asString(), v.get("signingKeys").get("mlDsa65").asString()));

        assertThat(request.verifyDeposit(key, v.get("signature").asString())).isTrue();
    }

    @Test
    void chunkDigestsAreReproducedExactly() throws Exception {
        JsonNode v = vectors().get("chunks");
        byte[] packet = new byte[v.get("size").asInt()];
        for (int i = 0; i < packet.length; i++) {
            packet[i] = (byte) (i % 251);
        }
        Path file = Files.write(dir.resolve("paquet.iu1"), packet);
        ChunkLayout layout = ChunkLayout.of(packet.length, v.get("chunkSize").asInt());

        assertThat(layout.chunkCount()).isEqualTo(v.get("chunkCount").asInt());
        List<String> expected = v.get("chunkDigests").valueStream().map(JsonNode::asString).toList();
        assertThat(Chunks.digests(file, layout)).containsExactlyElementsOf(expected);
        assertThat(Codec.sha256Hex(packet)).isEqualTo(v.get("packetDigest").asString());
    }

    @Test
    void envelopeAndSignedMessageOpenWithTheGivenKeys() throws Exception {
        JsonNode v = vectors().get("envelope");
        KeyFactory x = KeyFactory.getInstance("X25519");
        KeyFactory kem = KeyFactory.getInstance("ML-KEM-768");
        HybridKemKeyPair keys = new HybridKemKeyPair(
                new KeyPair(x.generatePublic(new X509EncodedKeySpec(b64(v.get("x25519Public")))),
                        x.generatePrivate(new PKCS8EncodedKeySpec(b64(v.get("x25519Pkcs8"))))),
                new KeyPair(kem.generatePublic(new X509EncodedKeySpec(b64(v.get("mlKem768Public")))),
                        kem.generatePrivate(new PKCS8EncodedKeySpec(b64(v.get("mlKem768Pkcs8"))))));

        assertThat(new String(MessageEnvelope.open(b64(v.get("envelope")), keys), StandardCharsets.UTF_8))
                .isEqualTo(v.get("content").asString());

        HybridPublicKey sender = HybridPublicKey.decode(new HybridPublicKey.Encoded(
                v.get("senderSigningKeys").get("ed25519").asString(),
                v.get("senderSigningKeys").get("mlDsa65").asString()));
        UUID senderId = UUID.fromString(v.get("senderBoxId").asString());
        UUID recipientId = UUID.fromString(v.get("recipientBoxId").asString());
        SignedMessage message = SignedMessage.open(b64(v.get("signedMessage")), keys,
                id -> id.equals(senderId) ? Optional.of(sender) : Optional.empty(), recipientId,
                java.time.Instant.parse(v.get("createdAt").asString()));
        assertThat(message.messageId()).isEqualTo(v.get("messageId").asString());
        assertThat(message.type()).isEqualTo(v.get("type").asString());
        assertThat(message.body()).isEqualTo(v.get("body").asString());
        assertThat(message.createdAt()).hasToString(v.get("createdAt").asString());
        assertThat(message.boxId()).isEqualTo(senderId);
        assertThat(message.to()).isEqualTo(recipientId);
    }
}
