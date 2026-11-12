package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.PairingService;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportPort;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PeerControllerTest {

    /** Dépôt minimal en mémoire. */
    private static final class Peers implements PeerRepository {
        final List<Peer> peers = new ArrayList<>();

        @Override
        public void save(Peer peer) {
            peers.add(peer);
        }

        @Override
        public Optional<Peer> findByName(String name) {
            return peers.stream().filter(p -> p.name().equals(name)).findFirst();
        }

        @Override
        public Optional<Peer> findByTransportPeerId(long id) {
            return peers.stream().filter(p -> p.transportPeerId() == id).findFirst();
        }

        @Override
        public List<Peer> findAll() {
            return peers;
        }

        @Override
        public void delete(String name) {
            peers.removeIf(p -> p.name().equals(name));
        }
    }

    /** Transport qui accepte tout et mémorise les liens rejoints. */
    private static final class Transport implements TransportPort {
        final List<String> joined = new ArrayList<>();
        Consumer<TransportEvent> listener = e -> { };

        @Override
        public String contactAddress() {
            return "https://relais.example/iu/v1/queues/" + "a".repeat(43);
        }

        @Override
        public void connect(String address) {
            joined.add(address);
        }

        @Override
        public void sendMessage(PeerId peer, String text) {
        }

        @Override
        public long sendFile(PeerId peer, Path file) {
            return 0;
        }

        @Override
        public void receiveFile(long fileId, Path target) {
        }

        @Override
        public void addListener(Consumer<TransportEvent> l) {
            listener = l;
        }

        @Override
        public void close() {
        }
    }

    private final Peers peers = new Peers();
    private final Transport transport = new Transport();
    private final PairingService pairing = new PairingService(peers, transport, Clock.systemUTC(), Duration.ofHours(1));
    private final PeerController controller = new PeerController(pairing, Providers.none(com.imagunity.directory.LocalDirectory.class));

    @Test
    void listsPeersPairedThroughTheDirectory() {
        pairing.start();
        pairing.join("box-a", "https://relais.example/iu/v1/queues/" + "b".repeat(43));
        transport.listener.accept(new TransportEvent.PeerConnected(new PeerId(3), "box-a"));

        assertThat(transport.joined).hasSize(1);
        assertThat(controller.peers()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("box-a");
            assertThat(p.displayName()).isEqualTo("box-a");
            assertThat(p.pairedAt()).isBeforeOrEqualTo(Instant.now());
            assertThat(p.directoryStatus()).isNull();
        });
    }
}
