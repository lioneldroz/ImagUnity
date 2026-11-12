package com.imagunity.release;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.SignedDocument;
import com.imagunity.directory.Suite;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Manifeste d'une version, signé par la clé hors ligne de l'éditeur.
 *
 * @param suite      {@code IU1}
 * @param product    produit ({@link #BOX} : logiciel de la box)
 * @param version    version affichée, par exemple {@code 0.2.0}
 * @param sequence   numéro de version, strictement croissant d'une version à la suivante : la box refuse de revenir à
 *                   un numéro inférieur à celui qu'elle a déjà exécuté
 * @param artifact   fichier de la version
 * @param releasedAt date de signature
 */
public record ReleaseManifest(String suite, String product, String version, long sequence, Artifact artifact,
                              Instant releasedAt) {

    /** Logiciel de la box. */
    public static final String BOX = "imagunity-box";
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ReleaseManifest {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(releasedAt, "releasedAt");
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Version invalide : " + version);
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("Le numéro de version commence à 1");
        }
    }

    /**
     * Fichier d'une version.
     *
     * @param name   nom du fichier, sans dossier
     * @param size   taille, en octets
     * @param sha256 empreinte SHA-256, en hexadécimal
     */
    public record Artifact(String name, long size, String sha256) {

        public Artifact {
            if (name == null || !NAME.matcher(name).matches() || name.contains("..")) {
                throw new IllegalArgumentException("Nom d'artefact invalide : " + name);
            }
            if (size < 1) {
                throw new IllegalArgumentException("Artefact vide");
            }
            if (sha256 == null || !SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("Empreinte d'artefact invalide");
            }
        }
    }

    /** Signe ce manifeste avec la clé de l'éditeur. */
    public SignedDocument sign(HybridKeyPair publisherKeys) {
        return SignedDocument.sign(ReleaseJson.write(this), publisherKeys);
    }

    /** Lit le contenu d'un manifeste signé, sans vérifier la signature. */
    static ReleaseManifest parse(SignedDocument document) {
        ReleaseManifest manifest;
        try {
            manifest = ReleaseJson.read(document.payload(), ReleaseManifest.class, "Manifeste de version");
        } catch (IllegalArgumentException e) {
            throw new ReleaseException("Manifeste de version invalide : " + e.getMessage(), e);
        }
        if (!Suite.ID.equals(manifest.suite())) {
            throw new ReleaseException("Suite cryptographique non prise en charge : " + manifest.suite());
        }
        return manifest;
    }

    /** {@code version (n° sequence)}. */
    public String label() {
        return version + " (n° " + sequence + ")";
    }
}
