package com.imagunity.directory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Lecture signée de l'annuaire (lecture réservée) : le lecteur signe le chemin demandé et l'heure, avec sa clé
 * d'identité (box enrôlée) ou la clé de fraîcheur de son organisation (service d'annuaire d'un partenaire reconnu).
 * Pas de corps ni d'anti-rejeu : une lecture rejouée ne donne que ce que ce lecteur pouvait déjà lire.
 *
 * @param reader identifiant du lecteur : identifiant de box, ou {@code organisation:<empreinte>}
 * @param keys   clé qui signe
 */
public record SignedRead(String reader, HybridKeyPair keys) {

    public static final String READER = "IU-Lecteur";
    public static final String TIMESTAMP = "IU-Lecture-Heure";
    public static final String SIGNATURE = "IU-Lecture-Signature";
    /** Préfixe du lecteur pour le service d'annuaire d'une organisation partenaire. */
    public static final String ORGANIZATION = "organisation:";
    /** Écart d'horloge toléré. */
    public static final Duration WINDOW = Duration.ofMinutes(5);

    static byte[] canonical(String reader, String pathAndQuery, long timestamp) {
        return ("IU1-lecture\n" + reader + "\n" + pathAndQuery + "\n" + timestamp).getBytes(StandardCharsets.UTF_8);
    }

    /** En-têtes à joindre à la lecture de {@code pathAndQuery} (chemin et paramètres, sans hôte). */
    public Map<String, String> headers(String pathAndQuery, Instant now) {
        long timestamp = now.getEpochSecond();
        HybridSignature signature = keys.sign(canonical(reader, pathAndQuery, timestamp));
        return Map.of(READER, reader, TIMESTAMP, Long.toString(timestamp), SIGNATURE,
                signature.ed25519() + "." + signature.mlDsa65());
    }

    /** Vrai si la signature de cette lecture est valide pour cette clé, et faite il y a moins de {@link #WINDOW}. */
    public static boolean verify(String reader, String pathAndQuery, String timestamp, String signature,
                                 HybridPublicKey key, Instant now) {
        if (reader == null || timestamp == null || signature == null) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - seconds) > WINDOW.toSeconds()) {
            return false;
        }
        String[] parts = signature.split("\\.", -1);
        if (parts.length != 2) {
            return false;
        }
        try {
            Base64.getDecoder().decode(parts[0]);
            Base64.getDecoder().decode(parts[1]);
            return HybridKeyPair.verify(key, canonical(reader, pathAndQuery, seconds),
                    new HybridSignature(Suite.ID, parts[0], parts[1]));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
