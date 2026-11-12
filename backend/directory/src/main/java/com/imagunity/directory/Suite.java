package com.imagunity.directory;

/** Suite cryptographique {@code IU1} : double signature Ed25519 + ML-DSA-65, empreintes SHA-256. */
public final class Suite {

    public static final String ID = "IU1";
    static final String ED25519 = "Ed25519";
    static final String ML_DSA_65 = "ML-DSA-65";

    private Suite() {
    }

    /** Refuse une autre suite que {@code IU1}. */
    static void require(String suite) {
        if (!ID.equals(suite)) {
            throw new DirectoryException("Suite cryptographique non prise en charge : " + suite);
        }
    }
}
