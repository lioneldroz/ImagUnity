package com.imagunity.transport.simplex;

import com.imagunity.transport.Invitation;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link TransportPort} au-dessus de simplex-chat : signalisation par SMP,
 * fichiers par XFTP, appairage par lien d'invitation à usage unique.
 */
public final class SimplexTransport implements TransportPort {

    private static final System.Logger LOG = System.getLogger(SimplexTransport.class.getName());

    private final SimplexChatClient client;
    private final SimplexEventMapper eventMapper = new SimplexEventMapper();
    private final List<Consumer<TransportEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Set<PeerId> connectedPeers = ConcurrentHashMap.newKeySet();
    private final long userId;

    /** Prend en charge le client et lit le profil actif de simplex-chat. */
    public SimplexTransport(SimplexChatClient client) {
        this.client = client;
        client.addEventListener(this::onSimplexEvent);
        JsonNode resp = expect(client.command(SimplexCommands.showActiveUser()), "activeUser");
        this.userId = resp.path("user").path("userId").asLong();
    }

    @Override
    public Invitation createInvitation() {
        JsonNode resp = expect(client.command(SimplexCommands.createInvitation(userId)), "invitation");
        JsonNode link = resp.path("connLinkInvitation");
        String shortLink = link.path("connShortLink").asString();
        return new Invitation(link.path("connFullLink").asString(), shortLink.isEmpty() ? null : shortLink);
    }

    @Override
    public void acceptInvitation(String invitationLink) {
        expect(client.command(SimplexCommands.connect(invitationLink)),
                "sentConfirmation", "sentInvitation", "contactAlreadyExists");
    }

    @Override
    public void sendMessage(PeerId peer, String text) {
        ObjectNode message = composed();
        message.putObject("msgContent").put("type", "text").put("text", text);
        send(peer, message);
    }

    @Override
    public long sendFile(PeerId peer, Path file) {
        if (!Files.isRegularFile(file)) {
            throw new TransportException("Fichier à envoyer introuvable : " + file);
        }
        ObjectNode message = composed();
        message.putObject("fileSource").put("filePath", file.toAbsolutePath().toString());
        message.putObject("msgContent").put("type", "file").put("text", "");
        JsonNode resp = send(peer, message);
        JsonNode fileId = resp.path("chatItems").path(0).path("chatItem").path("file").path("fileId");
        if (!fileId.isNumber()) {
            throw new TransportException("simplex-chat n'a pas renvoyé d'identifiant de fichier");
        }
        return fileId.asLong();
    }

    @Override
    public void receiveFile(long fileId, Path target) {
        expect(client.command(SimplexCommands.receiveFile(fileId, target)), "rcvFileAccepted");
    }

    @Override
    public void addListener(Consumer<TransportEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void close() {
        client.close();
    }

    private ObjectNode composed() {
        ObjectNode message = client.mapper().createObjectNode();
        message.putObject("mentions");
        return message;
    }

    private JsonNode send(PeerId peer, ObjectNode message) {
        ArrayNode messages = client.mapper().createArrayNode().add(message);
        String cmd = SimplexCommands.sendMessages(peer.value(), client.mapper().writeValueAsString(messages));
        return expect(client.command(cmd), "newChatItems");
    }

    private void onSimplexEvent(JsonNode resp) {
        for (TransportEvent event : eventMapper.map(resp)) {
            // contactConnected et contactSndReady peuvent signaler le même partenaire.
            if (event instanceof TransportEvent.PeerConnected pc && !connectedPeers.add(pc.peer())) {
                continue;
            }
            for (Consumer<TransportEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Erreur dans un écouteur du transport", e);
                }
            }
        }
    }

    private static JsonNode expect(JsonNode resp, String... expectedTypes) {
        String type = resp.path("type").asString();
        for (String expected : expectedTypes) {
            if (expected.equals(type)) {
                return resp;
            }
        }
        throw new TransportException("Réponse inattendue de simplex-chat : " + type
                + " (attendu : " + String.join(", ", expectedTypes) + ")");
    }
}
