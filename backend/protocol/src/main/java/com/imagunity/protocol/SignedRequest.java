package com.imagunity.protocol;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.HybridSignature;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Requête signée vers le relais (spécification § 6.3).
 *
 * <p>La signature porte sur la chaîne canonique, éléments séparés par un saut de ligne :
 * <pre>
 * IU1-req ‖ méthode ‖ chemin ‖ horodatage ‖ identifiant de requête ‖ SHA-256(corps) en hexadécimal
 * </pre>
 * <ul>
 *   <li>requête de <b>capacité</b> (morceau, file) : Ed25519 de la clé du morceau ou de la file ;</li>
 *   <li>requête de <b>dépôt</b> (création de morceau ou de file, dépôt de message) : double signature d'identité de la
 *       box, Ed25519 et ML-DSA-65, sous la forme {@code <ed25519>.<mlDsa65>} (base64url).</li>
 * </ul>
 *
 * @param method      méthode HTTP, en majuscules
 * @param path        chemin, sans hôte ni paramètres
 * @param timestamp   horodatage UTC, en secondes
 * @param requestId   16 octets aléatoires, base64url
 * @param bodySha256  empreinte SHA-256 du corps, en hexadécimal (corps vide : empreinte de zéro octet)
 */
public record SignedRequest(String method, String path, long timestamp, String requestId, String bodySha256) {

    /** En-tête : identifiant du morceau ou de la file (capacité), ou de la box (dépôt). */
    public static final String KEY_ID = "IU-Key-Id";
    public static final String TIMESTAMP = "IU-Timestamp";
    public static final String REQUEST_ID = "IU-Request-Id";
    public static final String SIGNATURE = "IU-Signature";

    private static final Pattern METHOD = Pattern.compile("[A-Z]{3,7}");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9/_.~-]{0,512}");
    private static final Pattern REQUEST = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public SignedRequest {
        if (method == null || !METHOD.matcher(method).matches()) {
            throw new ProtocolException("Méthode de requête invalide : " + method);
        }
        if (path == null || !PATH.matcher(path).matches() || path.contains("..")) {
            throw new ProtocolException("Chemin de requête invalide");
        }
        if (requestId == null || !REQUEST.matcher(requestId).matches()) {
            throw new ProtocolException("Identifiant de requête invalide");
        }
        if (bodySha256 == null || !SHA256.matcher(bodySha256).matches()) {
            throw new ProtocolException("Empreinte du corps invalide");
        }
    }

    /** Nouvelle requête : identifiant aléatoire, horodatage de l'instant donné. */
    public static SignedRequest of(String method, String path, byte[] body, Instant now) {
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        return new SignedRequest(method.toUpperCase(Locale.ROOT), path, now.getEpochSecond(), Codec.base64Url(id),
                Codec.sha256Hex(body == null ? new byte[0] : body));
    }

    /** Requête reçue, d'après ses en-têtes et son corps. */
    public static SignedRequest received(String method, String path, String timestamp, String requestId,
                                         byte[] body) {
        long seconds;
        try {
            seconds = Long.parseLong(Objects.requireNonNull(timestamp, "horodatage"));
        } catch (NumberFormatException | NullPointerException e) {
            throw new ProtocolException("Horodatage de requête absent ou invalide");
        }
        return new SignedRequest(method, path, seconds, requestId, Codec.sha256Hex(body == null ? new byte[0] : body));
    }

    /** Chaîne canonique signée. */
    public byte[] canonical() {
        return Codec.utf8("IU1-req\n" + method + "\n" + path + "\n" + timestamp + "\n" + requestId + "\n"
                + bodySha256);
    }

    /** Signature de capacité (Ed25519). */
    public String signCapability(PrivateKey key) {
        return Codec.base64Url(sign("Ed25519", key));
    }

    /** Vrai si la signature de capacité est valide pour cette clé publique Ed25519. */
    public boolean verifyCapability(PublicKey key, String signature) {
        return signature != null && verify("Ed25519", key, signature);
    }

    /** Signature de dépôt : double signature d'identité de la box. */
    public String signDeposit(HybridKeyPair identity) {
        HybridSignature signature = identity.sign(canonical());
        return Codec.base64Url(Base64.getDecoder().decode(signature.ed25519())) + "."
                + Codec.base64Url(Base64.getDecoder().decode(signature.mlDsa65()));
    }

    /** Vrai si les deux signatures de dépôt sont valides pour cette identité de box. */
    public boolean verifyDeposit(HybridPublicKey identity, String signature) {
        if (signature == null) {
            return false;
        }
        String[] parts = signature.split("\\.", -1);
        if (parts.length != 2) {
            return false;
        }
        try {
            return HybridKeyPair.verify(identity, canonical(), new HybridSignature("IU1",
                    Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(parts[0])),
                    Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(parts[1]))));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] sign(String algorithm, PrivateKey key) {
        try {
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(canonical());
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("Signature de requête impossible : " + e.getMessage(), e);
        }
    }

    private boolean verify(String algorithm, PublicKey key, String signature) {
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(canonical());
            return verifier.verify(Base64.getUrlDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
