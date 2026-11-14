package com.imagunity.directory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Code d'enrôlement à usage unique, tiré par le responsable pour une box et recopié par son installateur dans la
 * configuration de la box. La box joint à la demande d'enrôlement qu'elle envoie à l'annuaire une preuve calculée avec
 * ce code : l'outil du responsable reconnaît ainsi les demandes authentiques, sans appel téléphonique pour comparer
 * les empreintes, et sans que l'annuaire (qui ne connaît pas le code) puisse en fabriquer.
 *
 * <p>Format : 20 caractères base32 (100 bits aléatoires), par groupes de 4 : {@code 7KQ2-MX4P-…}. Majuscules,
 * minuscules, espaces et tirets sont indifférents à la saisie.
 *
 * <ul>
 *   <li>{@link #id(String) identifiant} : empreinte du code, transmise avec la demande pour la retrouver ; elle ne
 *       révèle pas le code ;</li>
 *   <li>{@link #proof(String, SignedDocument) preuve} : HMAC-SHA256 du contenu signé de la demande, avec une clé
 *       dérivée du code.</li>
 * </ul>
 */
public final class EnrollmentCode {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int LENGTH = 20;
    private static final Pattern NORMALIZED = Pattern.compile("[A-Z2-7]{" + LENGTH + "}");
    /** Identifiant transmis : 32 caractères hexadécimaux. */
    public static final Pattern ID = Pattern.compile("[0-9a-f]{32}");
    /** Preuve transmise : HMAC-SHA256 en base64url sans remplissage. */
    public static final Pattern PROOF = Pattern.compile("[A-Za-z0-9_-]{43}");

    private EnrollmentCode() {
    }

    /** Nouveau code, prêt à être recopié. */
    public static String generate() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < LENGTH; i++) {
            if (i > 0 && i % 4 == 0) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * Code sans séparateurs, en majuscules.
     *
     * @throws IllegalArgumentException si ce n'est pas un code d'enrôlement
     */
    public static String normalize(String code) {
        String normalized = code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!NORMALIZED.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Code d'enrôlement invalide : " + LENGTH
                    + " caractères attendus parmi A-Z et 2-7, par groupes de 4 (XXXX-XXXX-XXXX-XXXX-XXXX)");
        }
        return normalized;
    }

    /** Identifiant du code, transmis avec la demande. */
    public static String id(String code) {
        return HexFormat.of().formatHex(derive("imagunity-enrolement-id:", code), 0, 16);
    }

    /** Preuve qu'une demande d'enrôlement a été envoyée par la box qui détient ce code. */
    public static String proof(String code, SignedDocument request) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derive("imagunity-enrolement-cle:", code), "HmacSHA256"));
            byte[] tag = mac.doFinal(request.payload().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(tag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Vrai si la preuve correspond à cette demande et à ce code (comparaison en temps constant). */
    public static boolean verify(String code, SignedDocument request, String proof) {
        if (proof == null) {
            return false;
        }
        return MessageDigest.isEqual(proof(code, request).getBytes(StandardCharsets.US_ASCII),
                proof.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] derive(String context, String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest((context + normalize(code))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
