package com.imagunity.transport.simplex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.transport.Invitation;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Cas limites de l'adaptateur simplex-chat et des types du transport. */
class SimplexEdgeCasesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SimplexEventMapper eventMapper = new SimplexEventMapper();

    private List<TransportEvent> map(String json) {
        return eventMapper.map(mapper.readTree(json));
    }

    private static SimplexTransport transport(Function<String, String> responder) {
        SimplexChatClient client = new SimplexChatClient(JsonMapper.builder().build(), Duration.ofMillis(300));
        new FakeChatConnection(client, cmd -> cmd.equals("/user")
                ? "{\"type\":\"activeUser\",\"user\":{\"userId\":3}}"
                : responder.apply(cmd));
        return new SimplexTransport(client);
    }

    // --- Événements incomplets ou inattendus ---

    @Test
    void connectionEventWithoutContactIsIgnored() {
        assertThat(map("{\"type\":\"contactConnected\"}")).isEmpty();
    }

    @Test
    void receivedNonTextMessageIsIgnored() {
        assertThat(map("{\"type\":\"newChatItems\",\"chatItems\":[{\"chatInfo\":{\"type\":\"direct\","
                + "\"contact\":{\"contactId\":5}},\"chatItem\":{\"chatDir\":{\"type\":\"directRcv\"},"
                + "\"content\":{\"type\":\"rcvMsgContent\",\"msgContent\":{\"type\":\"image\",\"text\":\"\"}}}}]}"))
                .isEmpty();
    }

    @Test
    void receivedEventItemsAreIgnored() {
        assertThat(map("{\"type\":\"newChatItems\",\"chatItems\":[{\"chatInfo\":{\"type\":\"direct\","
                + "\"contact\":{\"contactId\":5}},\"chatItem\":{\"chatDir\":{\"type\":\"directRcv\"},\"file\":null,"
                + "\"content\":{\"type\":\"rcvDirectEvent\"}}}]}"))
                .isEmpty();
    }

    @Test
    void fileCompletionOutsideDirectChatIsIgnored() {
        assertThat(map("{\"type\":\"rcvFileComplete\",\"chatItem\":{\"chatInfo\":{\"type\":\"group\"},"
                + "\"chatItem\":{\"file\":{\"fileId\":1}}}}")).isEmpty();
        assertThat(map("{\"type\":\"rcvFileComplete\",\"chatItem\":{\"chatInfo\":{\"type\":\"direct\","
                + "\"contact\":{\"contactId\":5}},\"chatItem\":{}}}")).isEmpty();
        assertThat(map("{\"type\":\"sndFileCompleteXFTP\",\"chatItem\":{\"chatInfo\":{\"type\":\"local\"}},"
                + "\"fileTransferMeta\":{\"fileId\":1}}")).isEmpty();
    }

    @Test
    void fileCompletionWithoutPathGivesNullPath() {
        var events = map("{\"type\":\"rcvFileComplete\",\"chatItem\":{\"chatInfo\":{\"type\":\"direct\","
                + "\"contact\":{\"contactId\":5}},\"chatItem\":{\"file\":{\"fileId\":1}}}}");

        assertThat(events).containsExactly(new TransportEvent.FileReceived(new PeerId(5), 1, null));
    }

    // --- Réponses inattendues de simplex-chat ---

    @Test
    void unexpectedResponseTypeFails() {
        SimplexTransport transport = transport(cmd -> "{\"type\":\"somethingElse\"}");

        assertThatThrownBy(transport::createInvitation)
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("somethingElse")
                .hasMessageContaining("invitation");
    }

    @Test
    void invitationWithoutShortLink() {
        SimplexTransport transport = transport(cmd -> "{\"type\":\"invitation\",\"connLinkInvitation\":"
                + "{\"connFullLink\":\"https://simplex.chat/invitation#full\"}}");

        Invitation invitation = transport.createInvitation();

        assertThat(invitation.shortLink()).isNull();
        assertThat(invitation.preferredLink()).isEqualTo("https://simplex.chat/invitation#full");
    }

    @Test
    void sendFileWithoutFileIdInResponseFails(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("x.iu"), "x");
        SimplexTransport transport = transport(cmd -> "{\"type\":\"newChatItems\",\"chatItems\":[]}");

        assertThatThrownBy(() -> transport.sendFile(new PeerId(5), file))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("identifiant de fichier");
    }

    @Test
    void failingTransportListenerDoesNotStopOthers() {
        SimplexChatClient client = new SimplexChatClient(JsonMapper.builder().build(), Duration.ofMillis(300));
        var connection = new FakeChatConnection(client, cmd -> "{\"type\":\"activeUser\",\"user\":{\"userId\":3}}");
        SimplexTransport transport = new SimplexTransport(client);
        List<TransportEvent> events = new CopyOnWriteArrayList<>();
        transport.addListener(e -> {
            throw new IllegalStateException("écouteur en panne");
        });
        transport.addListener(events::add);

        connection.pushEvent("{\"type\":\"contactConnected\",\"contact\":{\"contactId\":5,\"localDisplayName\":\"b\"}}");

        assertThat(events).hasSize(1);
        transport.close();
        assertThat(connection.closed).isTrue();
    }

    // --- Commandes et types ---

    @Test
    void commandsRejectBlankArguments() {
        assertThatThrownBy(() -> SimplexCommands.connect(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SimplexCommands.connect("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SimplexCommands.receiveFile(1, Path.of("avec espace", "f.iu")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void peerIdIsReadable() {
        assertThat(new PeerId(12)).hasToString("peer-12");
    }

    @Test
    void transportExceptionKeepsMessage() {
        assertThat(new TransportException("échec")).hasMessage("échec").hasNoCause();
    }
}
