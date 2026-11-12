package com.imagunity.directory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Enveloppe commune des pièces signées : le texte JSON signé, tel quel, et sa double signature.
 *
 * <p>La signature porte sur les octets UTF-8 exacts de {@code payload} : aucune canonicalisation du JSON. Le texte
 * n'est relu qu'après vérification.
 *
 * @param payload    pièce signée (JSON)
 * @param signatures double signature {@code IU1}
 */
public record SignedDocument(String payload, HybridSignature signatures) {

    public SignedDocument {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(signatures, "signatures");
    }

    /** Signe ce texte. */
    public static SignedDocument sign(String payload, HybridKeyPair keys) {
        return new SignedDocument(payload, keys.sign(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** Vrai si la double signature est valide pour cette clé. */
    public boolean isSignedBy(HybridPublicKey key) {
        return HybridKeyPair.verify(key, payload.getBytes(StandardCharsets.UTF_8), signatures);
    }

    /** Forme échangée (fichier, annuaire). */
    public String toJson() {
        return Json.write(this);
    }

    public static SignedDocument fromJson(String json) {
        SignedDocument document = Json.read(json, SignedDocument.class, "Document signé");
        Objects.requireNonNull(document.signatures(), "signatures");
        return document;
    }
}
