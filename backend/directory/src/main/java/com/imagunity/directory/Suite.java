package com.imagunity.directory;

/**
 * Suite cryptographique {@code IU1} : double signature Ed25519 + ML-DSA-65, chiffrement par KEM hybride X25519 +
 * ML-KEM-768 (clés dérivées par HKDF-SHA256, contenu en AES-256-GCM), empreintes SHA-256.
 */
public final class Suite {

    public static final String ID = "IU1";
    static final String ED25519 = "Ed25519";
    static final String ML_DSA_65 = "ML-DSA-65";
    static final String X25519 = "X25519";
    static final String ML_KEM_768 = "ML-KEM-768";

    private Suite() {
    }

    /** Refuse une autre suite que {@code IU1}. */
    static void require(String suite) {
        if (!ID.equals(suite)) {
            throw new DirectoryException("Suite cryptographique non prise en charge : " + suite);
        }
    }
}
