package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferMessagesTest {

    private final TransferMessages messages = new TransferMessages();

    @Test
    void announcementRoundTrip() {
        TransferAnnouncement announcement = new TransferAnnouncement(UUID.randomUUID(), "groupe", "2.25.1",
                List.of(new TransferAnnouncement.Part(1, 2, "groupe-1-of-2.tar.zst", 100, "a".repeat(64)),
                        new TransferAnnouncement.Part(2, 2, "groupe-2-of-2.tar.zst", 50, "b".repeat(64))));

        String text = messages.encode(announcement);

        assertThat(text).contains("\"type\":\"imagunity.transfer.announce\"").contains("\"version\":1");
        assertThat(messages.decodeAnnouncement(text)).contains(announcement);
    }

    @Test
    void ignoresOtherMessages() {
        assertThat(messages.decodeAnnouncement("bonjour")).isEmpty();
        assertThat(messages.decodeAnnouncement("{\"type\":\"autre\",\"version\":1}")).isEmpty();
        assertThat(messages.decodeAnnouncement("{\"type\":\"imagunity.transfer.announce\",\"version\":2}")).isEmpty();
        assertThat(messages.decodeAnnouncement("{\"type\":\"imagunity.transfer.announce\",\"version\":1,"
                + "\"announcement\":{\"transferId\":\"pas-un-uuid\"}}")).isEmpty();
    }

    @Test
    void missingPartsGiveEmptyList() {
        assertThat(new TransferAnnouncement(UUID.randomUUID(), "g", "2.25.1", null).parts()).isEmpty();
    }

    @Test
    void ackRoundTrip() {
        TransferAck ack = new TransferAck(UUID.randomUUID(), "groupe", TransferAck.Result.FAILED, 0, "c".repeat(64),
                "PACS injoignable");

        String text = messages.encode(ack);

        assertThat(text).contains("\"type\":\"imagunity.transfer.ack\"").contains("\"result\":\"FAILED\"");
        assertThat(messages.decodeAck(text)).contains(ack);
        assertThat(messages.decodeAnnouncement(text)).isEmpty();
        assertThat(messages.decodeAck(messages.encode(new TransferAnnouncement(UUID.randomUUID(), "g", "2.25.1",
                List.of())))).isEmpty();
        assertThat(messages.decodeAck("{\"type\":\"imagunity.transfer.ack\",\"version\":1}")).isEmpty();
        assertThat(messages.decodeAck("{\"type\":\"imagunity.transfer.ack\",\"version\":1,"
                + "\"ack\":{\"result\":\"PEUT-ETRE\"}}")).isEmpty();
    }

    @Test
    void partsDigestDependsOnEveryPartButNotOnOrder() {
        var p1 = new TransferAnnouncement.Part(1, 2, "g-1-of-2.tar.zst", 100, "a".repeat(64));
        var p2 = new TransferAnnouncement.Part(2, 2, "g-2-of-2.tar.zst", 50, "b".repeat(64));
        String digest = TransferMessages.partsDigest("g", List.of(p1, p2));

        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(TransferMessages.partsDigest("g", List.of(p2, p1))).isEqualTo(digest);
        assertThat(TransferMessages.partsDigest("h", List.of(p1, p2))).isNotEqualTo(digest);
        assertThat(TransferMessages.partsDigest("g", List.of(p1,
                new TransferAnnouncement.Part(2, 2, "g-2-of-2.tar.zst", 51, "b".repeat(64))))).isNotEqualTo(digest);
    }
}
