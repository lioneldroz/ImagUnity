package com.imagunity.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.HybridKeyPair;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SignedRequestTest {

    private static final Instant NOW = Instant.parse("2026-11-12T17:00:00Z");
    private static final byte[] BODY = "corps".getBytes(StandardCharsets.UTF_8);

    @Test
    void capabilitySignatureCoversEveryElement() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SignedRequest request = SignedRequest.of("put", "/iu/v1/chunks/abc", BODY, NOW);
        String signature = request.signCapability(key.getPrivate());

        assertThat(request.method()).isEqualTo("PUT");
        assertThat(request.verifyCapability(key.getPublic(), signature)).isTrue();
        assertThat(SignedRequest.received("PUT", "/iu/v1/chunks/abc", Long.toString(NOW.getEpochSecond()),
                request.requestId(), BODY).verifyCapability(key.getPublic(), signature)).isTrue();
        // Chaque élément compte.
        assertThat(SignedRequest.received("POST", "/iu/v1/chunks/abc", "" + NOW.getEpochSecond(), request.requestId(),
                BODY).verifyCapability(key.getPublic(), signature)).isFalse();
        assertThat(SignedRequest.received("PUT", "/iu/v1/chunks/abd", "" + NOW.getEpochSecond(), request.requestId(),
                BODY).verifyCapability(key.getPublic(), signature)).isFalse();
        assertThat(SignedRequest.received("PUT", "/iu/v1/chunks/abc", "" + (NOW.getEpochSecond() + 1),
                request.requestId(), BODY).verifyCapability(key.getPublic(), signature)).isFalse();
        assertThat(SignedRequest.received("PUT", "/iu/v1/chunks/abc", "" + NOW.getEpochSecond(), request.requestId(),
                "autre".getBytes(StandardCharsets.UTF_8)).verifyCapability(key.getPublic(), signature)).isFalse();
        assertThat(request.verifyCapability(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic(),
                signature)).isFalse();
        assertThat(request.verifyCapability(key.getPublic(), null)).isFalse();
        assertThat(request.verifyCapability(key.getPublic(), "%%%")).isFalse();
        assertThat(SignedRequest.of("GET", "/iu/v1/queues/x", null, NOW).bodySha256()).isEqualTo(Codec.sha256Hex(new byte[0]));
        assertThat(SignedRequest.received("GET", "/x", "1", request.requestId(), null).bodySha256())
                .isEqualTo(Codec.sha256Hex(new byte[0]));
    }

    @Test
    void depositSignatureNeedsBothSignaturesOfTheBox() {
        HybridKeyPair box = HybridKeyPair.generate();
        SignedRequest request = SignedRequest.of("POST", "/iu/v1/chunks", BODY, NOW);
        String signature = request.signDeposit(box);
        String[] parts = signature.split("\\.");

        assertThat(request.verifyDeposit(box.publicKey(), signature)).isTrue();
        assertThat(request.verifyDeposit(HybridKeyPair.generate().publicKey(), signature)).isFalse();
        assertThat(request.verifyDeposit(box.publicKey(), parts[0])).as("Ed25519 seul").isFalse();
        assertThat(request.verifyDeposit(box.publicKey(), parts[0] + "." + parts[0])).isFalse();
        assertThat(request.verifyDeposit(box.publicKey(), "%%.%%")).isFalse();
        assertThat(request.verifyDeposit(box.publicKey(), null)).isFalse();
        assertThatThrownBy(() -> request.signCapability(HybridKeyPair.generate().mlDsa65().getPrivate()))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void malformedRequestsAreRefused() {
        String id = "AAECAwQFBgcICQoLDA0ODw";
        String sha = Codec.sha256Hex(BODY);
        assertThatThrownBy(() -> new SignedRequest("get", "/x", 1, id, sha)).hasMessageContaining("Méthode");
        assertThatThrownBy(() -> new SignedRequest(null, "/x", 1, id, sha)).hasMessageContaining("Méthode");
        assertThatThrownBy(() -> new SignedRequest("GET", "x", 1, id, sha)).hasMessageContaining("Chemin");
        assertThatThrownBy(() -> new SignedRequest("GET", "/a/../b", 1, id, sha)).hasMessageContaining("Chemin");
        assertThatThrownBy(() -> new SignedRequest("GET", null, 1, id, sha)).hasMessageContaining("Chemin");
        assertThatThrownBy(() -> new SignedRequest("GET", "/x?y=1", 1, id, sha)).hasMessageContaining("Chemin");
        assertThatThrownBy(() -> new SignedRequest("GET", "/x", 1, "court", sha)).hasMessageContaining("Identifiant");
        assertThatThrownBy(() -> new SignedRequest("GET", "/x", 1, null, sha)).hasMessageContaining("Identifiant");
        assertThatThrownBy(() -> new SignedRequest("GET", "/x", 1, id, "zz")).hasMessageContaining("Empreinte");
        assertThatThrownBy(() -> new SignedRequest("GET", "/x", 1, id, null)).hasMessageContaining("Empreinte");
        assertThatThrownBy(() -> SignedRequest.received("GET", "/x", "hier", id, BODY))
                .hasMessageContaining("Horodatage");
        assertThatThrownBy(() -> SignedRequest.received("GET", "/x", null, id, BODY))
                .hasMessageContaining("Horodatage");
    }

    @Test
    void replayedOrOutOfWindowRequestsAreRefused() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ReplayGuard guard = new ReplayGuard(clock);
        SignedRequest request = SignedRequest.of("GET", "/x", BODY, NOW);

        assertThat(guard.admit(request)).isEmpty();
        assertThat(guard.admit(request)).contains("requête rejouée");
        assertThat(guard.admit(SignedRequest.of("GET", "/x", BODY, NOW.minus(Duration.ofMinutes(6)))))
                .hasValueSatisfying(r -> assertThat(r).contains("horodatage"));
        assertThat(guard.admit(SignedRequest.of("GET", "/x", BODY, NOW.plus(Duration.ofMinutes(6))))).isPresent();
        assertThat(guard.admit(SignedRequest.of("GET", "/x", BODY, NOW.plus(Duration.ofMinutes(4))))).isEmpty();
    }

    @Test
    void replayMemoryIsBoundedAndForgetsExpiredRequests() {
        Instant[] now = {NOW};
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        ReplayGuard guard = new ReplayGuard(clock, Duration.ofMinutes(5), 2);
        SignedRequest first = SignedRequest.of("GET", "/x", BODY, NOW);
        assertThat(guard.admit(first)).isEmpty();
        assertThat(guard.admit(SignedRequest.of("GET", "/x", BODY, NOW))).isEmpty();
        assertThat(guard.admit(SignedRequest.of("GET", "/x", BODY, NOW))).contains("trop de requêtes récentes, réessayer plus tard");

        now[0] = NOW.plus(Duration.ofMinutes(11));
        assertThat(guard.admit(SignedRequest.of("GET", "/x", BODY, now[0]))).as("mémoire libérée").isEmpty();
        assertThat(clock.withZone(ZoneOffset.UTC).getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
