package com.imagunity.orchestration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import com.imagunity.domain.peer.Peer;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Format des messages de signalisation échangés entre box (JSON dans un message texte du transport).
 *
 * <pre>
 * {"type": "imagunity.transfer.announce", "version": 1, "announcement": { ... }}   émetteur → destinataire
 * {"type": "imagunity.transfer.ack",      "version": 1, "ack": { ... }}            destinataire → émetteur
 * </pre>
 * Un message d'un autre type, d'une autre version ou illisible est ignoré par le destinataire.
 *
 * <p>Avec l'annuaire ({@link MessageSigner}), les messages échangés sont signés par la box ({@code version 2}) et
 * vérifiés à la réception ; la forme gardée en base ({@link #store}) reste la forme non signée.
 */
public final class TransferMessages {

    public static final String ANNOUNCE = "imagunity.transfer.announce";
    public static final String ACK = "imagunity.transfer.ack";
    public static final int VERSION = 1;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MessageSigner signer;

    /** Messages non signés (sans annuaire). */
    public TransferMessages() {
        this(null);
    }

    /** @param signer signe et vérifie les messages échangés ({@code null} : non signés) */
    public TransferMessages(MessageSigner signer) {
        this.signer = signer;
    }

    /** Annonce à envoyer (signée avec l'annuaire). */
    public String encode(TransferAnnouncement announcement) {
        return signer == null ? store(announcement)
                : signer.seal(ANNOUNCE, mapper.writeValueAsString(announcement));
    }

    /** Accusé à envoyer (signé avec l'annuaire). */
    public String encode(TransferAck ack) {
        return signer == null ? envelope(ACK, "ack", ack) : signer.seal(ACK, mapper.writeValueAsString(ack));
    }

    /** Forme gardée en base d'une annonce reçue : toujours non signée. */
    public String store(TransferAnnouncement announcement) {
        return envelope(ANNOUNCE, "announcement", announcement);
    }

    /** Annonce reçue de ce partenaire (signature vérifiée avec l'annuaire). */
    public Optional<TransferAnnouncement> receiveAnnouncement(Peer from, String text) {
        return signer == null ? decodeAnnouncement(text)
                : signer.open(from, ANNOUNCE, text).flatMap(body -> read(body, TransferAnnouncement.class));
    }

    /** Accusé reçu de ce partenaire (signature vérifiée avec l'annuaire). */
    public Optional<TransferAck> receiveAck(Peer from, String text) {
        return signer == null ? decodeAck(text) : signer.open(from, ACK, text).flatMap(body -> read(body, TransferAck.class));
    }

    /** Vrai si le texte ressemble à une annonce (pour journaliser les annonces d'inconnus). */
    static boolean looksLikeAnnouncement(String text) {
        return text.contains("\"" + ANNOUNCE + "\"");
    }

    private <T> Optional<T> read(String body, Class<T> type) {
        try {
            return Optional.ofNullable(mapper.readValue(body, type));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /** Lit une annonce ; vide si le message n'en est pas une, ou pas dans une version prise en charge. */
    public Optional<TransferAnnouncement> decodeAnnouncement(String text) {
        return decode(text, ANNOUNCE, "announcement", TransferAnnouncement.class);
    }

    /** Lit un accusé ; vide si le message n'en est pas un, ou pas dans une version prise en charge. */
    public Optional<TransferAck> decodeAck(String text) {
        return decode(text, ACK, "ack", TransferAck.class);
    }

    /**
     * Empreinte des paquets d'un transfert : SHA-256 (hexadécimal) du texte
     * {@code <groupe>\n<rang>:<nombre>:<taille>:<sha256>\n...}, paquets par rang croissant.
     * L'émetteur la calcule sur ce qu'il a déposé, le destinataire sur l'annonce qu'il a vérifiée.
     */
    public static String partsDigest(String packageGroupId, List<TransferAnnouncement.Part> parts) {
        StringBuilder text = new StringBuilder(packageGroupId).append('\n');
        parts.stream().sorted(Comparator.comparingInt(TransferAnnouncement.Part::index))
                .forEach(p -> text.append(p.index()).append(':').append(p.count()).append(':').append(p.size())
                        .append(':').append(p.sha256()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String envelope(String type, String field, Object body) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", type);
        envelope.put("version", VERSION);
        envelope.set(field, mapper.valueToTree(body));
        return mapper.writeValueAsString(envelope);
    }

    private <T> Optional<T> decode(String text, String type, String field, Class<T> bodyType) {
        try {
            JsonNode envelope = mapper.readTree(text);
            if (!type.equals(envelope.path("type").asString()) || envelope.path("version").asInt() != VERSION) {
                return Optional.empty();
            }
            return Optional.ofNullable(mapper.treeToValue(envelope.path(field), bodyType));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}
