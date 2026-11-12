package com.imagunity.release;

import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.SignedDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Vérifie une version : manifeste signé par la clé épinglée de l'éditeur (les deux signatures), pour ce produit, et
 * artefact de la taille et de l'empreinte SHA-256 signées.
 */
public final class ReleaseVerifier {

    private final HybridPublicKey publisher;
    private final String product;

    public ReleaseVerifier(HybridPublicKey publisher, String product) {
        this.publisher = publisher;
        this.product = product;
    }

    /** Vérifie la signature et le produit d'un manifeste (sans l'artefact). */
    public ReleaseManifest verifyManifest(SignedDocument document) {
        if (!document.isSignedBy(publisher)) {
            throw new ReleaseException("Manifeste non signé par la clé de l'éditeur");
        }
        ReleaseManifest manifest = ReleaseManifest.parse(document);
        if (!product.equals(manifest.product())) {
            throw new ReleaseException("Manifeste d'un autre produit : " + manifest.product());
        }
        return manifest;
    }

    /** Vérifie un manifeste et l'artefact qu'il décrit. */
    public ReleaseManifest verify(SignedDocument document, Path artifact) throws IOException {
        ReleaseManifest manifest = verifyManifest(document);
        if (Files.size(artifact) != manifest.artifact().size()) {
            throw new ReleaseException("Artefact différent du manifeste signé (taille) : " + artifact);
        }
        if (!sha256(artifact).equals(manifest.artifact().sha256())) {
            throw new ReleaseException("Artefact différent du manifeste signé (empreinte) : " + artifact);
        }
        return manifest;
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
