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
}
