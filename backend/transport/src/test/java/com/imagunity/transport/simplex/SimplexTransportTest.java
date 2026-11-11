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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SimplexTransportTest {

    private final SimplexChatClient client =
            new SimplexChatClient(JsonMapper.builder().build(), Duration.ofMillis(300));
    private FakeChatConnection connection;
    private SimplexTransport transport;

    @BeforeEach
    void setUp() {
        connection = new FakeChatConnection(client, SimplexTransportTest::respond);
        transport = new SimplexTransport(client);
    }

    /** Réponses minimales de simplex-chat, d'après bots/api/COMMANDS.md. */
    private static String respond(String cmd) {
        if (cmd.equals("/user")) {
            return "{\"type\":\"activeUser\",\"user\":{\"userId\":3}}";
        }
        if (cmd.equals("/_connect 3")) {
            return "{\"type\":\"invitation\",\"connLinkInvitation\":"
                    + "{\"connFullLink\":\"https://simplex.chat/invitation#full\",\"connShortLink\":\"https://smp.example/i#short\"}}";
        }
        if (cmd.startsWith("/connect ")) {
            return "{\"type\":\"sentConfirmation\"}";
        }
        if (cmd.startsWith("/_send @5 json ") && cmd.contains("\"fileSource\"")) {
            return "{\"type\":\"newChatItems\",\"chatItems\":[{\"chatItem\":{\"file\":{\"fileId\":42}}}]}";
        }
        if (cmd.startsWith("/_send @5 json ")) {
            return "{\"type\":\"newChatItems\",\"chatItems\":[{\"chatItem\":{}}]}";
        }
        if (cmd.equals("/fcancel 42")) {
            return "{\"type\":\"sndFileCancelled\"}";
        }
        if (cmd.equals("/_contacts 3")) {
            return "{\"type\":\"contactsList\",\"contacts\":[{\"contactId\":5},{\"contactId\":6}]}";
        }
        if (cmd.equals("/_get chat @5 count=" + SimplexTransport.CATCH_UP_ITEMS)) {
            return "{\"type\":\"apiChat\",\"chat\":{\"chatInfo\":{\"type\":\"direct\",\"contact\":{\"contactId\":5}},"
                    + "\"chatItems\":[{\"chatDir\":{\"type\":\"directRcv\"},\"content\":{\"type\":\"rcvMsgContent\","
                    + "\"msgContent\":{\"type\":\"text\",\"text\":\"annonce\"}}}]}}";
        }
        if (cmd.equals("/_get chat @6 count=" + SimplexTransport.CATCH_UP_ITEMS)) {
            return "{\"type\":\"apiChat\",\"chat\":{\"chatInfo\":{\"type\":\"direct\",\"contact\":{\"contactId\":6}},"
                    + "\"chatItems\":[]}}";
        }
        if (cmd.startsWith("/freceive 42 ")) {
            return "{\"type\":\"rcvFileAccepted\"}";
        }
        return "{\"type\":\"chatCmdError\",\"chatError\":{\"type\":\"error\",\"errorType\":{\"type\":\"commandError\"}}}";
    }

    @Test
    void readsActiveUserAtStartup() {
        assertThat(connection.commands).containsExactly("/user");
    }

    @Test
    void createsInvitationForActiveUser() {
        Invitation invitation = transport.createInvitation();

        assertThat(connection.commands).contains("/_connect 3");
        assertThat(invitation.link()).isEqualTo("https://simplex.chat/invitation#full");
        assertThat(invitation.preferredLink()).isEqualTo("https://smp.example/i#short");
    }

    @Test
    void acceptsInvitation() {
        transport.acceptInvitation("https://simplex.chat/invitation#full");

        assertThat(connection.commands).contains("/connect https://simplex.chat/invitation#full");
    }

    @Test
    void sendsTextMessage() {
        transport.sendMessage(new PeerId(5), "examen prêt");

        assertThat(connection.commands.getLast())
                .isEqualTo("/_send @5 json [{\"mentions\":{},\"msgContent\":{\"type\":\"text\",\"text\":\"examen prêt\"}}]");
    }

    @Test
    void sendsFileAndReturnsItsId(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("etude.iu"), "contenu");

        long fileId = transport.sendFile(new PeerId(5), file);

        assertThat(fileId).isEqualTo(42);
        assertThat(connection.commands.getLast())
                .contains("\"fileSource\":{\"filePath\":")
                .contains("\"msgContent\":{\"type\":\"file\",\"text\":\"\"}");
    }

    @Test
    void refusesMissingFile(@TempDir Path dir) {
        assertThatThrownBy(() -> transport.sendFile(new PeerId(5), dir.resolve("absent.iu")))
                .isInstanceOf(TransportException.class);
    }

    @Test
    void receivesFile(@TempDir Path dir) {
        transport.receiveFile(42, dir.resolve("etude.iu"));

        assertThat(connection.commands.getLast()).startsWith("/freceive 42 ").endsWith("etude.iu");
    }

    @Test
    void peerConnectedIsReportedOnce() {
        List<TransportEvent> events = new CopyOnWriteArrayList<>();
        transport.addListener(events::add);
        String contact = "\"contact\":{\"contactId\":5,\"localDisplayName\":\"box-b\"}";

        connection.pushEvent("{\"type\":\"contactConnected\"," + contact + "}");
        connection.pushEvent("{\"type\":\"contactSndReady\"," + contact + "}");

        assertThat(events).containsExactly(new TransportEvent.PeerConnected(new PeerId(5), "box-b"));
    }

    @Test
    void rejectsInvitationLinkWithSpaces() {
        assertThatThrownBy(() -> transport.acceptInvitation("lien avec espace"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void catchUpReplaysRecentArrivalsOfEveryContact() {
        List<TransportEvent> events = new CopyOnWriteArrayList<>();
        transport.addListener(events::add);

        transport.catchUp();

        assertThat(connection.commands).contains("/_contacts 3", "/_get chat @5 count=200", "/_get chat @6 count=200");
        assertThat(events).containsExactly(new TransportEvent.MessageReceived(new PeerId(5), "annonce"));
    }

    @Test
    void discardsSentFileFromRelays() {
        transport.discardSentFile(42);

        assertThat(connection.commands).contains("/fcancel 42");
        assertThatThrownBy(() -> transport.discardSentFile(43)).isInstanceOf(TransportException.class);
    }
}
