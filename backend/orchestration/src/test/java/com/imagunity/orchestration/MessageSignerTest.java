package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageSignerTest {

    private final DirectoryFixture fixture = new DirectoryFixture();
    private final BoxIdentity boxA = fixture.box();
    private final BoxIdentity boxB = fixture.box();
    private final LocalDirectory directory;
    private final MessageSigner signerA;
    private final MessageSigner signerB;
    private final Peer aSeenByB;

    MessageSignerTest() {
        fixture.enroll(boxA);
        fixture.enroll(boxB);
        directory = fixture.directory(Clock.fixed(DirectoryFixture.NOW.plusSeconds(60), ZoneOffset.UTC));
        signerA = new MessageSigner(boxA, directory);
        signerB = new MessageSigner(boxB, directory);
        aSeenByB = new Peer("box-a", 9, "box-a", DirectoryFixture.NOW).boundTo(boxA.boxId());
    }

    @Test
    void signedMessageIsOpenedByItsRecipient() {
        String sealed = signerA.seal(TransferMessages.ACK, "{\"x\":1}");

        assertThat(sealed).contains("\"version\":2").contains(boxA.boxId().toString());
        assertThat(signerB.open(aSeenByB, TransferMessages.ACK, sealed)).contains("{\"x\":1}");
        assertThat(signerB.open(aSeenByB, TransferMessages.ANNOUNCE, sealed)).as("autre type").isEmpty();
        assertThat(signerB.open(aSeenByB, TransferMessages.ACK, "pas du json")).isEmpty();
    }

    @Test
    void unsignedForgedOrMisattributedMessagesAreRefused() {
        String sealed = signerA.seal(TransferMessages.ACK, "{\"x\":1}");
        Peer unbound = new Peer("box-a", 9, "box-a", DirectoryFixture.NOW);
        Peer boundToB = unbound.boundTo(boxB.boxId());
        BoxIdentity outsider = new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate());
        String fromOutsider = new MessageSigner(outsider, directory).seal(TransferMessages.ACK, "{\"x\":1}");

        assertThat(signerB.open(aSeenByB, TransferMessages.ACK,
                "{\"type\":\"imagunity.transfer.ack\",\"version\":1,\"ack\":{}}")).as("non signé").isEmpty();
        assertThat(signerB.open(unbound, TransferMessages.ACK, sealed)).as("contact non lié").isEmpty();
        assertThat(signerB.open(boundToB, TransferMessages.ACK, sealed)).as("autre box").isEmpty();
        assertThat(signerB.open(aSeenByB.boundTo(outsider.boxId()), TransferMessages.ACK, fromOutsider))
                .as("box hors annuaire").isEmpty();
        assertThat(signerB.open(aSeenByB, TransferMessages.ACK, sealed.replace("{\\\"x\\\":1}", "{\\\"x\\\":2}")))
                .as("corps falsifié").isEmpty();
        assertThat(signerB.open(aSeenByB, TransferMessages.ACK, sealed.replace(boxA.boxId().toString(), "pas-un-uuid")))
                .as("enveloppe mal formée").isEmpty();
        assertThat(List.of(sealed)).isNotEmpty();
    }

    @Test
    void transferMessagesAreSignedOnTheWireAndStoredUnsigned() {
        TransferMessages messagesA = new TransferMessages(signerA);
        TransferMessages messagesB = new TransferMessages(signerB);
        TransferAnnouncement announcement = new TransferAnnouncement(UUID.randomUUID(), "g", "2.25.1", List.of(),
                DirectoryFixture.NOW);
        TransferAck ack = new TransferAck(announcement.transferId(), "g", TransferAck.Result.RESTORED, 3, "d", null);
        Peer bSeenByA = new Peer("box-b", 5, "box-b", DirectoryFixture.NOW).boundTo(boxB.boxId());

        assertThat(messagesB.receiveAnnouncement(aSeenByB, messagesA.encode(announcement))).contains(announcement);
        assertThat(messagesA.receiveAck(bSeenByA, messagesB.encode(ack))).contains(ack);
        assertThat(messagesB.receiveAnnouncement(aSeenByB, new TransferMessages().encode(announcement)))
                .as("annonce non signée refusée").isEmpty();
        assertThat(messagesA.store(announcement)).contains("\"version\":1");
        assertThat(messagesA.decodeAnnouncement(messagesA.store(announcement))).contains(announcement);
        assertThat(messagesA.receiveAck(bSeenByA, signerB.seal(TransferMessages.ACK, "pas du json"))).isEmpty();
        assertThat(TransferMessages.looksLikeAnnouncement(messagesA.encode(announcement))).isTrue();
        assertThat(TransferMessages.looksLikeAnnouncement("bonjour")).isFalse();
    }
}
