package com.imagunity.transport.simplex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.transport.TransportException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SimplexChatClientTest {

    private final SimplexChatClient client =
            new SimplexChatClient(JsonMapper.builder().build(), Duration.ofMillis(300));

    @Test
    void returnsTheResponseWithTheSameCorrelationId() {
        new FakeChatConnection(client, cmd -> "{\"type\":\"activeUser\",\"user\":{\"userId\":7}}");

        JsonNode resp = client.command("/user");

        assertThat(resp.path("user").path("userId").asLong()).isEqualTo(7);
    }

    @Test
    void sendsCommandAndCorrelationIdAsJson() {
        var connection = new FakeChatConnection(client, cmd -> "{\"type\":\"ok\"}");

        client.command("/user");
        client.command("/_connect 1");

        assertThat(connection.commands).containsExactly("/user", "/_connect 1");
    }

    @Test
    void chatCmdErrorBecomesAnException() {
        new FakeChatConnection(client, cmd ->
                "{\"type\":\"chatCmdError\",\"chatError\":{\"type\":\"error\",\"errorType\":{\"type\":\"noActiveUser\"}}}");

        assertThatThrownBy(() -> client.command("/user"))
                .isInstanceOf(SimplexCommandException.class)
                .hasMessageContaining("/user")
                .hasMessageContaining("error/noActiveUser");
    }

    @Test
    void errorMessageDoesNotLeakCommandArguments() {
        new FakeChatConnection(client, cmd -> "{\"type\":\"chatCmdError\",\"chatError\":{\"type\":\"error\"}}");

        assertThatThrownBy(() -> client.command("/connect https://simplex.chat/invitation#secret"))
                .hasMessageNotContaining("secret");
    }

    @Test
    void messagesWithoutCorrelationIdAreEvents() {
        var connection = new FakeChatConnection(client, cmd -> null);
        List<String> received = new CopyOnWriteArrayList<>();
        client.addEventListener(resp -> received.add(resp.path("type").asString()));

        connection.pushEvent("{\"type\":\"contactConnected\"}");
        connection.pushEvent("{\"type\":\"someFutureUndocumentedEvent\",\"extra\":1}");

        assertThat(received).containsExactly("contactConnected", "someFutureUndocumentedEvent");
    }

    @Test
    void invalidJsonIsIgnored() {
        new FakeChatConnection(client, cmd -> null);
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        client.addEventListener(received::add);

        client.onMessage("pas du json");

        assertThat(received).isEmpty();
    }

    @Test
    void missingResponseTimesOut() {
        new FakeChatConnection(client, cmd -> null);

        assertThatThrownBy(() -> client.command("/user"))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("Pas de réponse");
    }

    @Test
    void closingFailsPendingCommands() {
        var connection = new FakeChatConnection(client, cmd -> null);
        CompletableFuture<JsonNode> pending = client.commandAsync("/user");

        client.close();

        assertThat(connection.closed).isTrue();
        assertThat(pending).isCompletedExceptionally();
    }

    @Test
    void commandWithoutConnectionFails() {
        assertThatThrownBy(() -> client.command("/user"))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("non connecté");
    }
}
