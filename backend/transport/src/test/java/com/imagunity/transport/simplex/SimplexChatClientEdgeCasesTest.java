package com.imagunity.transport.simplex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.transport.TransportException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Cas d'erreur du client bas niveau. */
class SimplexChatClientEdgeCasesTest {

    private final SimplexChatClient client =
            new SimplexChatClient(JsonMapper.builder().build(), Duration.ofMillis(300));

    @Test
    void sendFailureFailsTheCommand() {
        client.attach(new ChatConnection() {
            @Override
            public void send(String text) {
                throw new IllegalStateException("socket fermée");
            }

            @Override
            public void close() {
            }
        });

        assertThatThrownBy(() -> client.command("/user"))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("Envoi impossible");
    }

    @Test
    void connectionLossFailsPendingCommandWithTransportException() {
        new FakeChatConnection(client, cmd -> {
            client.onClosed(new java.io.IOException("réseau coupé"));
            return null;
        });

        assertThatThrownBy(() -> client.command("/user"))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("fermée");
    }

    @Test
    void interruptedWaitFails() {
        new FakeChatConnection(client, cmd -> null);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.command("/user"))
                    .isInstanceOf(TransportException.class)
                    .hasMessageContaining("interrompue");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void messagesWithoutRespAndLateResponsesAreIgnored() {
        new FakeChatConnection(client, cmd -> null);
        List<String> events = new CopyOnWriteArrayList<>();
        client.addEventListener(resp -> events.add(resp.path("type").asString()));

        client.onMessage("{\"corrId\":\"42\"}");
        client.onMessage("{\"corrId\":\"999\",\"resp\":{\"type\":\"activeUser\"}}");

        assertThat(events).isEmpty();
    }

    @Test
    void failingListenerDoesNotStopOthers() {
        new FakeChatConnection(client, cmd -> null);
        List<String> events = new CopyOnWriteArrayList<>();
        client.addEventListener(resp -> {
            throw new IllegalStateException("écouteur en panne");
        });
        client.addEventListener(resp -> events.add(resp.path("type").asString()));

        client.onMessage("{\"resp\":{\"type\":\"contactConnected\"}}");

        assertThat(events).containsExactly("contactConnected");
    }

    @Test
    void closeWithoutConnectionIsHarmless() {
        client.close();
    }

    @Test
    void commandExceptionDescribesAgentAndStoreErrors() {
        var mapper = JsonMapper.builder().build();

        var agent = new SimplexCommandException("/freceive 1 /x",
                mapper.readTree("{\"type\":\"errorAgent\",\"agentError\":{\"type\":\"NETWORK\"}}"));
        var store = new SimplexCommandException("/user",
                mapper.readTree("{\"type\":\"errorStore\",\"storeError\":{\"type\":\"userNotFound\"}}"));
        var bare = new SimplexCommandException("/user", mapper.readTree("{\"type\":\"error\"}"));

        assertThat(agent).hasMessageContaining("(/freceive)").hasMessageContaining("errorAgent/NETWORK");
        assertThat(store).hasMessageContaining("errorStore/userNotFound");
        assertThat(bare).hasMessageEndingWith(": error");
        assertThat(agent.chatError().path("type").asString()).isEqualTo("errorAgent");
    }
}
