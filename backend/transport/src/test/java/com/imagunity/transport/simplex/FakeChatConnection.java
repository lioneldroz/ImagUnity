package com.imagunity.transport.simplex;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Faux simplex-chat : enregistre les commandes reçues et répond selon une fonction
 * {@code commande -> JSON du champ resp} ({@code null} = pas de réponse).
 */
final class FakeChatConnection implements ChatConnection {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SimplexChatClient client;
    private final Function<String, String> responder;
    final List<String> commands = new CopyOnWriteArrayList<>();
    volatile boolean closed;

    FakeChatConnection(SimplexChatClient client, Function<String, String> responder) {
        this.client = client;
        this.responder = responder;
        client.attach(this);
    }

    @Override
    public void send(String text) {
        JsonNode message = mapper.readTree(text);
        String cmd = message.path("cmd").asString();
        commands.add(cmd);
        String resp = responder.apply(cmd);
        if (resp != null) {
            client.onMessage("{\"corrId\":\"" + message.path("corrId").asString() + "\",\"resp\":" + resp + "}");
        }
    }

    /** Simule un événement poussé par simplex-chat. */
    void pushEvent(String resp) {
        client.onMessage("{\"resp\":" + resp + "}");
    }

    @Override
    public void close() {
        closed = true;
    }
}
