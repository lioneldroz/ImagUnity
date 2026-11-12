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

    private static final String ADDRESS = "https://relais.example/iu/v1/queues/" + "b".repeat(43);

    @Test
    void theOperatorNamesThePeerItConnectsTo() {
        pairing.join("box-b", ADDRESS);

        transport.emit(connected(5, "profil-annonce"));

        assertThat(transport.acceptedLinks).containsExactly(ADDRESS);
        assertThat(pairing.peers()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("box-b");
            assertThat(p.transportPeerId()).isEqualTo(5);
            assertThat(p.displayName()).isEqualTo("profil-annonce");
            assertThat(p.pairedAt()).isEqualTo(clock.now);
            assertThat(p.boxId()).as("liée par la poignée de main").isNull();
        });
    }

    @Test
    void incomingConnectionsAreProvisionalUntilTheHandshake() {
        transport.emit(connected(12, "box-a"));

        assertThat(peers.findByName("attente-12")).hasValueSatisfying(p -> assertThat(p.boxId()).isNull());
    }

    @Test
    void pairingExpires() {
        pairing.join("box-b", ADDRESS);
        clock.now = clock.now.plus(Duration.ofHours(2));

        transport.emit(connected(5, "box-b"));

        assertThat(pairing.peers()).extracting(Peer::name).containsExactly("attente-5");
    }

    @Test
    void onlyOneConnectionPerPairing() {
        pairing.join("box-b", ADDRESS);
        transport.emit(connected(5, "box-b"));

        transport.emit(connected(6, "autre"));

        assertThat(pairing.peers()).extracting(Peer::name).containsExactlyInAnyOrder("attente-6", "box-b");
    }

    @Test
    void alreadyPairedContactIsNotRenamed() {
        pairing.join("box-b", ADDRESS);
        transport.emit(connected(5, "box-b"));
        pairing.join("box-c", ADDRESS);

        transport.emit(connected(5, "box-b")); // reconnexion d'un contact connu

        assertThat(peers.findByTransportPeerId(5)).map(Peer::name).contains("box-b");
        transport.emit(connected(6, "box-c")); // l'appairage en cours reste disponible
        assertThat(peers.findByName("box-c")).map(Peer::transportPeerId).contains(6L);
    }

    @Test
    void failedJoinLeavesNoPendingPairing() {
        transport.failAccept = true;

        assertThatThrownBy(() -> pairing.join("box-a", ADDRESS)).isInstanceOf(TransportException.class);
        transport.emit(connected(9, "box-a"));

        assertThat(pairing.peers()).extracting(Peer::name).containsExactly("attente-9");
    }

    @Test
    void rejectsInvalidNames() {
        assertThatThrownBy(() -> pairing.join("a b", ADDRESS)).isInstanceOf(IllegalArgumentException.class);
        assertThat(transport.acceptedLinks).isEmpty();
    }

    @Test
    void otherEventsAreIgnored() {
        pairing.join("box-b", ADDRESS);

        transport.emit(new TransportEvent.MessageReceived(new PeerId(5), "x"));

        assertThat(pairing.peers()).isEmpty();
    }
}
