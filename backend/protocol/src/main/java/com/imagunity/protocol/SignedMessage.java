package com.imagunity.protocol;

import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKemPublicKey;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.SignedDocument;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Message IU-MSG de bout en bout (spécification § 8.4) : contenu signé par l'identité de la box émettrice (double
 * signature, sur les octets exacts du contenu), puis chiffré pour la box destinataire ({@link MessageEnvelope}).
 *
 * @param suite     {@code IU1}
 * @param messageId identifiant unique (16 octets, base64url) : le destinataire rejette un message déjà vu
 * @param createdAt date d'émission
 * @param type      type de message, par exemple {@code file.announce}
 * @param boxId     box émettrice
 * @param to        box destinataire : signée avec le reste, elle empêche une box de faire suivre à une troisième un
 *                  message qu'on lui a adressé
 * @param body      corps (texte, en général JSON)
 */
public record SignedMessage(String suite, String messageId, Instant createdAt, String type, UUID boxId, UUID to,
                            String body) {

    /** Âge maximal d'un message à l'ouverture (au-delà, il peut avoir été oublié par l'anti-rejeu). */
    public static final java.time.Duration MAX_AGE = java.time.Duration.ofDays(8);
    /** Avance d'horloge tolérée sur la date d'émission. */
    public static final java.time.Duration CLOCK_SKEW = java.time.Duration.ofMinutes(10);

    /** Émetteur absent de l'annuaire : le message peut être gardé, l'entrée pouvant arriver plus tard. */
    public static final class UnknownSender extends ProtocolException {
        UnknownSender(UUID boxId) {
            super("Box émettrice absente de l'annuaire : " + boxId);
        }
    }

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9.-]{0,63}");
    private static final Pattern ID16 = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public SignedMessage {
        if (!"IU1".equals(suite)) {
            throw new ProtocolException("Suite non prise en charge : " + suite);
        }
        if (messageId == null || !ID16.matcher(messageId).matches()) {
            throw new ProtocolException("Identifiant de message invalide");
        }
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new ProtocolException("Type de message invalide : " + type);
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(boxId, "boxId");
        if (to == null) {
            throw new ProtocolException("Message sans destinataire");
        }
        Objects.requireNonNull(body, "body");
    }

    /** Nouveau message, d'identifiant aléatoire. */
    public static SignedMessage of(String type, UUID boxId, UUID to, String body, Instant now) {
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        return new SignedMessage("IU1", Codec.base64Url(id), now, type, boxId, to, body);
    }

    /** Signe ce message avec l'identité de la box, puis le chiffre pour le destinataire. */
    public byte[] seal(HybridKeyPair identity, HybridKemPublicKey recipient) {
        String signed = SignedDocument.sign(Codec.toJson(this), identity).toJson();
        return MessageEnvelope.seal(signed.getBytes(StandardCharsets.UTF_8), recipient);
    }

    /**
     * Ouvre une enveloppe : déchiffre avec les clés de cette box, puis vérifie la signature de la box émettrice.
     *
     * @param signingKeys clés de signature d'une box d'après l'annuaire (vide : box inconnue)
     * @param self        cette box : le message doit lui être destiné
     * @param now         date de l'ouverture : le message doit dater de moins de {@link #MAX_AGE}
     * @throws UnknownSender     si l'émetteur est absent de l'annuaire
     * @throws ProtocolException si l'enveloppe ne s'ouvre pas, si la signature est invalide, si le message est destiné
     *                           à une autre box, trop ancien ou daté du futur
     */
    public static SignedMessage open(byte[] envelope, HybridKemKeyPair keys,
                                     Function<UUID, Optional<HybridPublicKey>> signingKeys, UUID self, Instant now) {
        String text = new String(MessageEnvelope.open(envelope, keys), StandardCharsets.UTF_8);
        SignedDocument document;
        try {
            document = SignedDocument.fromJson(text);
        } catch (DirectoryException e) {
            throw new ProtocolException("Message signé illisible", e);
        }
        SignedMessage message = Codec.fromJson(document.payload(), SignedMessage.class, "Message signé");
        HybridPublicKey key = signingKeys.apply(message.boxId()).orElseThrow(() -> new UnknownSender(message.boxId()));
        if (!document.isSignedBy(key)) {
            throw new ProtocolException("Signature du message invalide");
        }
        if (!message.to().equals(self)) {
            throw new ProtocolException("Message signé par " + message.boxId() + " pour une autre box ("
                    + message.to() + ") : refusé");
        }
        if (message.createdAt().isBefore(now.minus(MAX_AGE))) {
            throw new ProtocolException("Message trop ancien (" + message.createdAt() + ")");
        }
        if (message.createdAt().isAfter(now.plus(CLOCK_SKEW))) {
            throw new ProtocolException("Message daté du futur (" + message.createdAt() + ")");
        }
        return message;
    }
}
