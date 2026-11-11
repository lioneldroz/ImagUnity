package com.imagunity.orchestration;

import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Format des messages de signalisation échangés entre box (JSON dans un message texte du transport).
 *
 * <pre>
 * {"type": "imagunity.transfer.announce", "version": 1, "announcement": { ... }}
 * </pre>
 * Un message d'un autre type, d'une autre version ou illisible est ignoré par le destinataire.
 */
public final class TransferMessages {

    public static final String ANNOUNCE = "imagunity.transfer.announce";
    public static final int VERSION = 1;

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String encode(TransferAnnouncement announcement) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", ANNOUNCE);
        envelope.put("version", VERSION);
        envelope.set("announcement", mapper.valueToTree(announcement));
        return mapper.writeValueAsString(envelope);
    }

    /** Lit une annonce ; vide si le message n'en est pas une, ou pas dans une version prise en charge. */
    public Optional<TransferAnnouncement> decodeAnnouncement(String text) {
        try {
            JsonNode envelope = mapper.readTree(text);
            if (!ANNOUNCE.equals(envelope.path("type").asString()) || envelope.path("version").asInt() != VERSION) {
                return Optional.empty();
            }
            return Optional.of(mapper.treeToValue(envelope.path("announcement"), TransferAnnouncement.class));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}
