package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.PairingService;
import com.imagunity.transport.DisabledTransport;
import com.imagunity.transport.Invitation;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
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
import org.springframework.http.HttpStatus;

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
    }

    /** Transport qui accepte tout et mémorise les liens rejoints. */
    private static final class Transport implements TransportPort {
        final List<String> joined = new ArrayList<>();
        Consumer<TransportEvent> listener = e -> { };

        @Override
        public Invitation createInvitation() {
            return new Invitation("https://simplex.chat/invitation#x", "https://s/i#x");
        }

        @Override
        public void acceptInvitation(String link) {
            joined.add(link);
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
    void createsInvitation() {
        var response = controller.invite(new PeerController.InvitationRequest("box-b"));

        assertThat(response).isEqualTo(new PeerController.InvitationResponse("box-b", "https://simplex.chat/invitation#x"));
    }

    @Test
    void joinsAndListsPeers() {
        pairing.start();
        controller.join(new PeerController.JoinRequest("box-a", "https://simplex.chat/invitation#y"));
        transport.listener.accept(new TransportEvent.PeerConnected(new PeerId(3), "box-a"));

        assertThat(transport.joined).containsExactly("https://simplex.chat/invitation#y");
        assertThat(controller.peers()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("box-a");
            assertThat(p.displayName()).isEqualTo("box-a");
            assertThat(p.pairedAt()).isBeforeOrEqualTo(Instant.now());
        });
    }

    @Test
    void rejectsMissingLink() {
        assertThatThrownBy(() -> controller.join(new PeerController.JoinRequest("box-a", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.join(new PeerController.JoinRequest("box-a", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsErrorsToHttpStatuses() {
        assertThat(controller.invalidRequest(new IllegalArgumentException("nom invalide")).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
        var problem = new PeerController(new PairingService(peers, new DisabledTransport(), Clock.systemUTC(),
                Duration.ofHours(1)), Providers.none(com.imagunity.directory.LocalDirectory.class));
        TransportException failure = null;
        try {
            problem.invite(new PeerController.InvitationRequest("box-b"));
        } catch (TransportException e) {
            failure = e;
        }
        assertThat(failure).isNotNull();
        assertThat(controller.transportUnavailable(failure).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(controller.transportUnavailable(failure).getDetail()).contains("désactivé");
    }
}
