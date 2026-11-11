package com.imagunity.transport.simplex;

import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Traduit les événements simplex-chat en {@link TransportEvent}.
 *
 * <p>Seuls les contacts directs sont pris en compte (pas de groupes). Les événements
 * non reconnus donnent une liste vide.
 *
 * <p>Référence : {@code bots/api/EVENTS.md} et {@code TYPES.md} du dépôt simplex-chat.
 */
final class SimplexEventMapper {

    List<TransportEvent> map(JsonNode resp) {
        return switch (resp.path("type").asString()) {
            case "contactConnected", "contactSndReady" -> peerConnected(resp.path("contact"));
            case "newChatItems" -> newChatItems(resp.path("chatItems"));
            case "rcvFileComplete" -> rcvFileComplete(resp.path("chatItem"));
            case "sndFileCompleteXFTP" -> sndFileComplete(resp);
            case "rcvFileError" -> List.of(new TransportEvent.FileFailed(
                    resp.path("rcvFileTransfer").path("fileId").asLong(),
                    "réception : " + resp.path("agentError").path("type").asString()));
            case "sndFileError" -> List.of(new TransportEvent.FileFailed(
                    resp.path("fileTransferMeta").path("fileId").asLong(),
                    "envoi : " + resp.path("errorMessage").asString()));
            // Événements non documentés dans bots/api, relevés dans src/Simplex/Chat/Controller.hs (v7.1.2).
            case "sndFileProgressXFTP" -> List.of(new TransportEvent.FileProgress(
                    resp.path("fileTransferMeta").path("fileId").asLong(),
                    TransportEvent.Direction.SENDING,
                    resp.path("sentSize").asLong(),
                    resp.path("totalSize").asLong()));
            case "rcvFileProgressXFTP" -> List.of(new TransportEvent.FileProgress(
                    resp.path("rcvFileTransfer").path("fileId").asLong(),
                    TransportEvent.Direction.RECEIVING,
                    resp.path("receivedSize").asLong(),
                    resp.path("totalSize").asLong()));
            default -> List.of();
        };
    }

    private static List<TransportEvent> peerConnected(JsonNode contact) {
        if (contact.isMissingNode()) {
            return List.of();
        }
        return List.of(new TransportEvent.PeerConnected(
                new PeerId(contact.path("contactId").asLong()),
                contact.path("localDisplayName").asString()));
    }

    private static List<TransportEvent> newChatItems(JsonNode chatItems) {
        List<TransportEvent> events = new ArrayList<>();
        for (JsonNode item : chatItems) {
            PeerId peer = directContact(item);
            JsonNode chatItem = item.path("chatItem");
            boolean received = "directRcv".equals(chatItem.path("chatDir").path("type").asString());
            if (peer == null || !received) {
                continue;
            }
            JsonNode file = chatItem.path("file");
            if (!file.isMissingNode() && !file.isNull()) {
                events.add(new TransportEvent.FileOffered(peer,
                        file.path("fileId").asLong(),
                        file.path("fileName").asString(),
                        file.path("fileSize").asLong()));
                continue;
            }
            JsonNode content = chatItem.path("content");
            if ("rcvMsgContent".equals(content.path("type").asString())
                    && "text".equals(content.path("msgContent").path("type").asString())) {
                events.add(new TransportEvent.MessageReceived(peer, content.path("msgContent").path("text").asString()));
            }
        }
        return events;
    }

    private static List<TransportEvent> rcvFileComplete(JsonNode item) {
        PeerId peer = directContact(item);
        JsonNode file = item.path("chatItem").path("file");
        if (peer == null || file.isMissingNode()) {
            return List.of();
        }
        String path = file.path("fileSource").path("filePath").asString();
        return List.of(new TransportEvent.FileReceived(peer, file.path("fileId").asLong(),
                path.isEmpty() ? null : Path.of(path)));
    }

    private static List<TransportEvent> sndFileComplete(JsonNode resp) {
        PeerId peer = directContact(resp.path("chatItem"));
        if (peer == null) {
            return List.of();
        }
        return List.of(new TransportEvent.FileSent(peer, resp.path("fileTransferMeta").path("fileId").asLong()));
    }

    /** Contact direct d'un AChatItem, ou {@code null} pour un groupe ou une note locale. */
    private static PeerId directContact(JsonNode aChatItem) {
        JsonNode chatInfo = aChatItem.path("chatInfo");
        if (!"direct".equals(chatInfo.path("type").asString())) {
            return null;
        }
        return new PeerId(chatInfo.path("contact").path("contactId").asLong());
    }
}
