package com.imagunity.directory;

/**
 * Double signature {@code IU1} : les deux doivent être valides.
 *
 * @param suite   {@code IU1}
 * @param ed25519 signature Ed25519 (base64)
 * @param mlDsa65 signature ML-DSA-65 (base64)
 */
public record HybridSignature(String suite, String ed25519, String mlDsa65) {
}
