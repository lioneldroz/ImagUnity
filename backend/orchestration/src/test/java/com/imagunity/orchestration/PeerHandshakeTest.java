package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PeerHandshakeTest {

    private static final PeerId B_SEEN_BY_A = new PeerId(5);
    private static final PeerId A_SEEN_BY_B = new PeerId(9);

    private final Clock clock = Clock.fixed(DirectoryFixture.NOW.plusSeconds(60), ZoneOffset.UTC);
    private final DirectoryFixture fixture = new DirectoryFixture();
    private final BoxIdentity boxA = fixture.box();
    private final BoxIdentity boxB = fixture.box();
    private final FakeTransport transportA = new FakeTransport();
    private final FakeTransport transportB = new FakeTransport();
    private final InMemoryRepositories.Peers peersA = new InMemoryRepositories.Peers();
    private final InMemoryRepositories.Peers peersB = new InMemoryRepositories.Peers();
    private PeerHandshake handshakeA;
    private PeerHandshake handshakeB;
    private LocalDirectory directoryA;
    private int deliveredToB;
    private int deliveredToA;

    @BeforeEach
    void setUp() {
        fixture.enroll(boxA);
        fixture.enroll(boxB);
        peersA.save(new Peer("box-b", B_SEEN_BY_A.value(), "box-b", DirectoryFixture.NOW));
        peersB.save(new Peer("box-a", A_SEEN_BY_B.value(), "box-a", DirectoryFixture.NOW));
        directoryA = fixture.directory(clock);
        handshakeA = new PeerHandshake(peersA, transportA, boxA, directoryA, Runnable::run);
        handshakeB = new PeerHandshake(peersB, transportB, boxB, fixture.directory(clock), Runnable::run);
    }

    /** Fait circuler les messages entre A et B jusqu'à ce que plus rien ne bouge. */
    private void relay() {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (; deliveredToB < transportA.messages.size(); deliveredToB++) {
                transportB.emit(new TransportEvent.MessageReceived(A_SEEN_BY_B,
                        transportA.messages.get(deliveredToB).text()));
                moved = true;
            }
            for (; deliveredToA < transportB.messages.size(); deliveredToA++) {
                transportA.emit(new TransportEvent.MessageReceived(B_SEEN_BY_A,
                        transportB.messages.get(deliveredToA).text()));
                moved = true;
            }
        }
    }

    private Optional<UUID> boundA() {
        return peersA.findByName("box-b").map(Peer::boxId);
    }

    @Test
    void bothSidesBindTheirPartnerToItsEnrolledBox() {
        handshakeA.start();
        handshakeB.start();

        relay();

        assertThat(boundA()).contains(boxB.boxId());
        assertThat(peersB.findByName("box-a").orElseThrow().boxId()).isEqualTo(boxA.boxId());
        int sent = transportA.messages.size();
        handshakeA.greetUnbound();
        assertThat(transportA.messages).as("plus rien à saluer").hasSize(sent);
    }

    @Test
    void connectionTriggersTheHandshake() {
        handshakeA.start();
        handshakeB.start();
        transportA.messages.clear();
        transportB.messages.clear();
        deliveredToA = 0;
        deliveredToB = 0;
        peersA.save(new Peer("box-b", B_SEEN_BY_A.value(), "box-b", DirectoryFixture.NOW));

        transportA.emit(new TransportEvent.PeerConnected(B_SEEN_BY_A, "box-b"));
        relay();

        assertThat(boundA()).contains(boxB.boxId());
    }

    @Test
    void unknownBoxIsBoundAfterTheNextSynchronization() {
        BoxIdentity late = fixture.box();
        PeerHandshake handshakeLate = new PeerHandshake(peersB, transportB, late, fixture.directory(clock),
                Runnable::run);
        handshakeA.start();
        handshakeLate.start();
        relay();
        assertThat(boundA()).as("box absente de l'annuaire de A").isEmpty();

        fixture.enroll(late);
        directoryA.synchronize(() -> List.copyOf(fixture.published));
        handshakeA.greetUnbound();
        relay();

        assertThat(boundA()).contains(late.boxId());
    }

    @Test
    void revokedOrForgedIdentitiesAreNotBound() {
        BoxIdentity impostor = new BoxIdentity(boxB.boxId(), HybridKeyPair.generate());
        PeerHandshake forged = new PeerHandshake(peersB, transportB, impostor, fixture.directory(clock), Runnable::run);
        handshakeA.start();
        forged.start();

        relay();

        assertThat(boundA()).as("signature avec d'autres clés que celles de l'annuaire").isEmpty();
    }

    @Test
    void inactiveBoxIsNotBound() {
        Clock later = Clock.fixed(DirectoryFixture.NOW.plus(Duration.ofDays(31)), ZoneOffset.UTC);
        PeerHandshake expiredView = new PeerHandshake(peersA, transportA, boxA, fixture.directory(later),
                Runnable::run);
        expiredView.start();
        handshakeB.start();

        relay();

        assertThat(boundA()).isEmpty();
    }

    @Test
    void proofWithoutMatchingChallengeOrForAnotherBoxIsRefused() {
        handshakeB.start();
        // B répond à un défi que A n'a jamais envoyé.
        transportB.emit(new TransportEvent.MessageReceived(A_SEEN_BY_B,
                "{\"type\":\"imagunity.peer.hello\",\"version\":1,\"boxId\":\"" + boxA.boxId()
                        + "\",\"challenge\":\"inventé\"}"));
        handshakeA.start();
        transportA.emit(new TransportEvent.MessageReceived(B_SEEN_BY_A, transportB.messages.getFirst().text()));
        assertThat(boundA()).isEmpty();

        peersA.save(peersA.findByName("box-b").orElseThrow().boundTo(UUID.randomUUID()));
        PeerHandshake again = new PeerHandshake(peersA, transportA, boxA, directoryA, Runnable::run);
        transportA.messages.clear();
        deliveredToB = 0;
        again.onEvent(new TransportEvent.PeerConnected(B_SEEN_BY_A, "box-b")); // déjà lié : rien
        assertThat(transportA.messages).isEmpty();
    }

    @Test
    void partnerBoundToAnotherBoxStaysBound() {
        UUID other = UUID.randomUUID();
        handshakeA.start();
        handshakeB.start();
        // A a envoyé son défi ; entre-temps, le contact a été lié à une autre box.
        peersA.save(peersA.findByName("box-b").orElseThrow().boundTo(other));

        relay();

        assertThat(boundA()).contains(other);
    }

    @Test
    void malformedOrForeignMessagesAreIgnored() {
        handshakeA.start();
        int sent = transportA.messages.size();
        List<String> junk = List.of("pas du json", "{\"type\":\"autre\",\"version\":1}",
                "{\"type\":\"imagunity.peer.hello\",\"version\":2}",
                "{\"type\":\"imagunity.peer.hello\",\"version\":1,\"boxId\":\"pas-un-uuid\",\"challenge\":\"c\"}",
                "{\"type\":\"imagunity.peer.proof\",\"version\":1,\"boxId\":\"" + boxB.boxId()
                        + "\",\"challenge\":\"c\",\"signature\":\"pas un objet\"}");

        junk.forEach(text -> transportA.emit(new TransportEvent.MessageReceived(B_SEEN_BY_A, text)));
        transportA.emit(new TransportEvent.MessageReceived(new PeerId(66),
                "{\"type\":\"imagunity.peer.hello\",\"version\":1,\"boxId\":\"" + boxB.boxId() + "\",\"challenge\":\"c\"}"));
        transportA.emit(new TransportEvent.FileSent(B_SEEN_BY_A, 1));

        assertThat(transportA.messages).hasSize(sent);
        assertThat(boundA()).isEmpty();
    }

    @Test
    void sendFailureIsOnlyLogged() {
        transportA.failSendMessage = true;

        handshakeA.start();

        assertThat(transportA.messages).isEmpty();
    }
}
