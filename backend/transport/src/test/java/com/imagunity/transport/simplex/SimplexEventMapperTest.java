package com.imagunity.transport.simplex;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Formes JSON reprises de bots/api/EVENTS.md et TYPES.md (simplex-chat), réduites aux champs utiles. */
class SimplexEventMapperTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SimplexEventMapper eventMapper = new SimplexEventMapper();

    private static final String DIRECT_CONTACT_5 =
            "\"chatInfo\":{\"type\":\"direct\",\"contact\":{\"contactId\":5,\"localDisplayName\":\"box-b\"}}";

    private java.util.List<TransportEvent> map(String json) {
        return eventMapper.map(mapper.readTree(json));
    }

    @Test
    void contactConnected() {
        var events = map("{\"type\":\"contactConnected\",\"contact\":{\"contactId\":5,\"localDisplayName\":\"box-b\"}}");

        assertThat(events).containsExactly(new TransportEvent.PeerConnected(new PeerId(5), "box-b"));
    }

    @Test
    void receivedTextMessage() {
        var events = map("{\"type\":\"newChatItems\",\"chatItems\":[{" + DIRECT_CONTACT_5 + ",\"chatItem\":{"
                + "\"chatDir\":{\"type\":\"directRcv\"},"
                + "\"content\":{\"type\":\"rcvMsgContent\",\"msgContent\":{\"type\":\"text\",\"text\":\"bonjour\"}}}}]}");

        assertThat(events).containsExactly(new TransportEvent.MessageReceived(new PeerId(5), "bonjour"));
    }

    @Test
    void receivedFileOffer() {
        var events = map("{\"type\":\"newChatItems\",\"chatItems\":[{" + DIRECT_CONTACT_5 + ",\"chatItem\":{"
                + "\"chatDir\":{\"type\":\"directRcv\"},"
                + "\"content\":{\"type\":\"rcvMsgContent\",\"msgContent\":{\"type\":\"file\",\"text\":\"\"}},"
                + "\"file\":{\"fileId\":42,\"fileName\":\"etude.iu\",\"fileSize\":1048576,"
                + "\"fileStatus\":{\"type\":\"rcvInvitation\"},\"fileProtocol\":\"xftp\"}}}]}");

        assertThat(events).containsExactly(new TransportEvent.FileOffered(new PeerId(5), 42, "etude.iu", 1048576));
    }

    @Test
    void ownSentMessagesAreIgnored() {
        var events = map("{\"type\":\"newChatItems\",\"chatItems\":[{" + DIRECT_CONTACT_5 + ",\"chatItem\":{"
                + "\"chatDir\":{\"type\":\"directSnd\"},"
                + "\"content\":{\"type\":\"sndMsgContent\",\"msgContent\":{\"type\":\"text\",\"text\":\"moi\"}}}}]}");

        assertThat(events).isEmpty();
    }

    @Test
    void groupMessagesAreIgnored() {
        var events = map("{\"type\":\"newChatItems\",\"chatItems\":[{\"chatInfo\":{\"type\":\"group\"},\"chatItem\":{"
                + "\"chatDir\":{\"type\":\"groupRcv\"},"
                + "\"content\":{\"type\":\"rcvMsgContent\",\"msgContent\":{\"type\":\"text\",\"text\":\"x\"}}}}]}");

        assertThat(events).isEmpty();
    }

    @Test
    void fileReceptionComplete() {
        var events = map("{\"type\":\"rcvFileComplete\",\"chatItem\":{" + DIRECT_CONTACT_5 + ",\"chatItem\":{"
                + "\"chatDir\":{\"type\":\"directRcv\"},"
                + "\"file\":{\"fileId\":42,\"fileName\":\"etude.iu\",\"fileSize\":10,"
                + "\"fileSource\":{\"filePath\":\"/data/files/etude.iu\"}}}}}");

        assertThat(events).containsExactly(
                new TransportEvent.FileReceived(new PeerId(5), 42, Path.of("/data/files/etude.iu")));
    }

    @Test
    void fileUploadComplete() {
        var events = map("{\"type\":\"sndFileCompleteXFTP\",\"chatItem\":{" + DIRECT_CONTACT_5 + ",\"chatItem\":{}},"
                + "\"fileTransferMeta\":{\"fileId\":42}}");

        assertThat(events).containsExactly(new TransportEvent.FileSent(new PeerId(5), 42));
    }

    @Test
    void fileErrors() {
        assertThat(map("{\"type\":\"sndFileError\",\"fileTransferMeta\":{\"fileId\":42},\"errorMessage\":\"quota\"}"))
                .containsExactly(new TransportEvent.FileFailed(42, "envoi : quota"));
        assertThat(map("{\"type\":\"rcvFileError\",\"rcvFileTransfer\":{\"fileId\":43},\"agentError\":{\"type\":\"NETWORK\"}}"))
                .containsExactly(new TransportEvent.FileFailed(43, "réception : NETWORK"));
    }

    @Test
    void unknownEventsAreIgnored() {
        assertThat(map("{\"type\":\"hostConnected\",\"protocol\":\"smp\"}")).isEmpty();
        assertThat(map("{\"type\":\"somethingNew\"}")).isEmpty();
    }
}
