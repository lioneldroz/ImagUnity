package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HybridKeysTest {

    private static final HybridKeyPair KEYS = HybridKeyPair.generate();
    private static final HybridKeyPair OTHER = HybridKeyPair.generate();
    private static final byte[] DATA = "entrée".getBytes(StandardCharsets.UTF_8);

    @Test
    void bothSignaturesMustBeValid() {
        HybridSignature signature = KEYS.sign(DATA);
        HybridSignature otherData = KEYS.sign("autre".getBytes(StandardCharsets.UTF_8));

        assertThat(HybridKeyPair.verify(KEYS.publicKey(), DATA, signature)).isTrue();
        assertThat(HybridKeyPair.verify(OTHER.publicKey(), DATA, signature)).as("autre clé").isFalse();
        assertThat(HybridKeyPair.verify(KEYS.publicKey(), "falsifié".getBytes(), signature)).isFalse();
        assertThat(HybridKeyPair.verify(KEYS.publicKey(), DATA,
                new HybridSignature("IU1", signature.ed25519(), otherData.mlDsa65()))).as("ML-DSA fausse").isFalse();
        assertThat(HybridKeyPair.verify(KEYS.publicKey(), DATA,
                new HybridSignature("IU1", otherData.ed25519(), signature.mlDsa65()))).as("Ed25519 fausse").isFalse();
    }

    @Test
    void malformedSignaturesAreRejected() {
        HybridSignature signature = KEYS.sign(DATA);

        assertThat(HybridKeyPair.verify(KEYS.publicKey(), DATA, null)).isFalse();
        assertThat(HybridKeyPair.verify(KEYS.publicKey(), DATA,
                new HybridSignature("IU2", signature.ed25519(), signature.mlDsa65()))).isFalse();
        assertThat(HybridKeyPair.verify(KEYS.publicKey(), DATA,
                new HybridSignature("IU1", null, signature.mlDsa65()))).isFalse();
        assertThat(HybridKeyPair.verify(KEYS.publicKey(), DATA,
                new HybridSignature("IU1", "pas du base64 !", signature.mlDsa65()))).isFalse();
    }

    @Test
    void publicKeyRoundTripsThroughItsPublishedForm() {
        HybridPublicKey key = KEYS.publicKey();

        HybridPublicKey back = HybridPublicKey.decode(key.encoded());

        assertThat(back.fingerprint()).isEqualTo(key.fingerprint()).hasSize(64);
        assertThat(back.shortCode()).isEqualTo(key.shortCode());
        assertThatThrownBy(() -> HybridPublicKey.decode(null)).isInstanceOf(DirectoryException.class);
        assertThatThrownBy(() -> HybridPublicKey.decode(new HybridPublicKey.Encoded(key.encoded().ed25519(), null)))
                .isInstanceOf(DirectoryException.class);
        assertThatThrownBy(() -> HybridPublicKey.decode(new HybridPublicKey.Encoded("AAAA", key.encoded().mlDsa65())))
                .isInstanceOf(DirectoryException.class).hasMessageContaining("Ed25519");
        assertThatThrownBy(() -> new HybridPublicKey(null, key.mlDsa65())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HybridKeyPair(KEYS.ed25519(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shortCodeIsReadableOverThePhone() {
        String code = KEYS.publicKey().shortCode();

        assertThat(code).matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){5}");
        assertThat(code).isNotEqualTo(OTHER.publicKey().shortCode());
    }
}
