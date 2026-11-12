package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.peer.Peer;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PairingServiceTest {

    /** Horloge réglable. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-11T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();
    private final InMemoryRepositories.Peers peers = new InMemoryRepositories.Peers();
    private final FakeTransport transport = new FakeTransport();
    private PairingService pairing;

    @BeforeEach
    void setUp() {
        pairing = new PairingService(peers, transport, clock, Duration.ofHours(1));
        pairing.start();
    }

    private static TransportEvent.PeerConnected connected(long id, String displayName) {
        return new TransportEvent.PeerConnected(new PeerId(id), displayName);
    }

    @Test
    void inviterNamesThePeerLocally() {
        assertThat(pairing.invite("box-b").link()).isEqualTo("https://simplex.chat/invitation#test");

        transport.emit(connected(5, "profil-annonce"));

        assertThat(pairing.peers()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("box-b");
            assertThat(p.transportPeerId()).isEqualTo(5);
            assertThat(p.displayName()).isEqualTo("profil-annonce");
            assertThat(p.pairedAt()).isEqualTo(clock.now);
        });
    }

    @Test
    void joinerNamesThePeerLocally() {
        pairing.join("box-a", "https://simplex.chat/invitation#lien");

        transport.emit(connected(9, "box-a"));

        assertThat(transport.acceptedLinks).containsExactly("https://simplex.chat/invitation#lien");
        assertThat(peers.findByName("box-a")).map(Peer::transportPeerId).contains(9L);
    }

    @Test
    void unexpectedConnectionIsIgnored() {
        transport.emit(connected(5, "intrus"));

        assertThat(pairing.peers()).isEmpty();
    }

    @Test
    void pairingExpires() {
        pairing.invite("box-b");
        clock.now = clock.now.plus(Duration.ofHours(2));

        transport.emit(connected(5, "box-b"));

        assertThat(pairing.peers()).isEmpty();
    }

    @Test
    void onlyOneConnectionPerPairing() {
        pairing.invite("box-b");
        transport.emit(connected(5, "box-b"));

        transport.emit(connected(6, "autre"));

        assertThat(pairing.peers()).extracting(Peer::transportPeerId).containsExactly(5L);
    }

    @Test
    void alreadyPairedContactIsNotRenamed() {
        pairing.invite("box-b");
        transport.emit(connected(5, "box-b"));
        pairing.invite("box-c");

        transport.emit(connected(5, "box-b")); // reconnexion d'un contact connu

        assertThat(peers.findByTransportPeerId(5)).map(Peer::name).contains("box-b");
        transport.emit(connected(6, "box-c")); // l'appairage en cours reste disponible
        assertThat(peers.findByName("box-c")).map(Peer::transportPeerId).contains(6L);
    }

    @Test
    void failedJoinLeavesNoPendingPairing() {
        transport.failAccept = true;

        assertThatThrownBy(() -> pairing.join("box-a", "lien")).isInstanceOf(TransportException.class);
        transport.emit(connected(9, "box-a"));

        assertThat(pairing.peers()).isEmpty();
    }

    @Test
    void rejectsInvalidNames() {
        assertThatThrownBy(() -> pairing.invite("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pairing.join("a b", "lien")).isInstanceOf(IllegalArgumentException.class);
        assertThat(transport.acceptedLinks).isEmpty();
    }

    @Test
    void otherEventsAreIgnored() {
        pairing.invite("box-b");

        transport.emit(new TransportEvent.MessageReceived(new PeerId(5), "x"));

        assertThat(pairing.peers()).isEmpty();
    }

    @Test
    void withTheDirectoryIncomingContactsAreAcceptedAsProvisionalPeers() {
        InMemoryRepositories.Peers repository = new InMemoryRepositories.Peers();
        FakeTransport fake = new FakeTransport();
        PairingService service = new PairingService(repository, fake, java.time.Clock.systemUTC(),
                java.time.Duration.ofHours(1), Runnable::run, true);
        service.start();

        fake.emit(new com.imagunity.transport.TransportEvent.ContactRequested(4, "box-a"));
        fake.emit(new com.imagunity.transport.TransportEvent.PeerConnected(new com.imagunity.transport.PeerId(12),
                "box-a"));

        org.assertj.core.api.Assertions.assertThat(fake.acceptedRequests).containsExactly(4L);
        org.assertj.core.api.Assertions.assertThat(repository.findByName("attente-12"))
                .hasValueSatisfying(p -> org.assertj.core.api.Assertions.assertThat(p.boxId()).isNull());
        fake.failAcceptRequest = true;
        fake.emit(new com.imagunity.transport.TransportEvent.ContactRequested(5, "box-z"));
        org.assertj.core.api.Assertions.assertThat(fake.acceptedRequests).containsExactly(4L);
    }

    @Test
    void withoutTheDirectoryContactRequestsAreIgnored() {
        FakeTransport fake = new FakeTransport();
        PairingService service = new PairingService(new InMemoryRepositories.Peers(), fake,
                java.time.Clock.systemUTC(), java.time.Duration.ofHours(1));
        service.start();

        fake.emit(new com.imagunity.transport.TransportEvent.ContactRequested(4, "box-a"));

        org.assertj.core.api.Assertions.assertThat(fake.acceptedRequests).isEmpty();
    }
}
