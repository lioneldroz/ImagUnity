package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.EntryCache;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import com.imagunity.domain.peer.Peer;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.orchestration.PairingService;
import com.imagunity.transport.DisabledTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DirectoryControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC);
    private final Organization organization = new Organization("chu-exemple", HybridKeyPair.generate());
    private final BoxIdentity me = new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate());
    private final BoxIdentity partner = new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate());

    /** Transport : adresse de contact de cette box, connexions demandées. */
    private static final class Transport implements com.imagunity.transport.TransportPort {
        final List<String> joined = new java.util.ArrayList<>();
        boolean noAddress;

        @Override
        public String contactAddress() {
            if (noAddress) {
                throw new com.imagunity.transport.TransportException("transport désactivé");
            }
            return "simplex:/contact#box-a";
        }

        @Override
        public void connect(String link) {
            joined.add(link);
        }

        @Override
        public void sendMessage(com.imagunity.transport.PeerId peer, String text) {
        }

        @Override
        public long sendFile(com.imagunity.transport.PeerId peer, java.nio.file.Path file) {
            return 0;
        }

        @Override
        public void receiveFile(long fileId, java.nio.file.Path target) {
        }

        @Override
        public void addListener(java.util.function.Consumer<com.imagunity.transport.TransportEvent> listener) {
        }

        @Override
        public void close() {
        }
    }

    private final Transport transport = new Transport();
    private final MemoryRepositories.Peers peers = new MemoryRepositories.Peers();
    private final BoxIdentity silent = new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate());
    private final BoxIdentity revoked = new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate());

    private LocalDirectory directory() {
        EntryCache noCache = new EntryCache() {
            @Override
            public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
            }

            @Override
            public List<SignedDocument> entries() {
                return List.of();
            }
        };
        LocalDirectory directory = new LocalDirectory(new EntryVerifier("chu-exemple", organization.publicKey(),
                SerialMemory.inMemory(), clock), noCache, clock);
        SignedDocument revokedEntry = organization.enroll(EnrollmentRequest.create(revoked.keys(), revoked.boxId(),
                "Site D", Map.of("iu", "simplex:/contact#d"), NOW), NOW, Duration.ofDays(30), 1);
        directory.synchronize(() -> List.of(
                organization.enroll(EnrollmentRequest.create(partner.keys(), partner.boxId(), "Site B",
                        Map.of("iu", "simplex:/contact#b"), NOW), NOW, Duration.ofDays(30), 1),
                organization.enroll(EnrollmentRequest.create(me.keys(), me.boxId(), "Site A",
                        Map.of("iu", "simplex:/contact#a"), NOW), NOW, Duration.ofDays(30), 1),
                organization.enroll(EnrollmentRequest.create(silent.keys(), silent.boxId(), "Site C", Map.of(), NOW),
                        NOW, Duration.ofDays(30), 1),
                organization.revoke(revokedEntry, NOW, "Box volée")));
        return directory;
    }

    private DirectoryController controller(boolean enabled) {
        return new DirectoryController(
                enabled ? Providers.of(BoxIdentity.class, me) : Providers.none(BoxIdentity.class),
                enabled ? Providers.of(LocalDirectory.class, directory()) : Providers.none(LocalDirectory.class),
                new BoxProperties("box-a"), clock, transport,
                new PairingService(peers, transport, clock, Duration.ofHours(1)), peers);
    }

    @Test
    void showsIdentitySynchronizationAndKnownBoxes() {
        DirectoryController.DirectoryView view = controller(true).directory();

        assertThat(view.enabled()).isTrue();
        assertThat(view.boxId()).isEqualTo(me.boxId());
        assertThat(view.shortCode()).isEqualTo(me.keys().publicKey().shortCode());
        assertThat(view.lastSync().accepted()).isEqualTo(4);
        assertThat(view.lastSync().error()).isNull();
        assertThat(view.boxes()).extracting(DirectoryController.BoxView::displayName)
                .containsExactly("Site A", "Site B", "Site C", "Site D");
        assertThat(view.boxes()).extracting(DirectoryController.BoxView::status).containsExactly(
                BoxEntry.Status.ACTIVE, BoxEntry.Status.ACTIVE, BoxEntry.Status.ACTIVE, BoxEntry.Status.REVOKED);
        assertThat(view.boxes()).extracting(DirectoryController.BoxView::reachable)
                .containsExactly(true, true, false, true);
        assertThat(view.boxes()).extracting(DirectoryController.BoxView::encrypted)
                .containsExactly(false, false, false, false);
    }

    @Test
    void withoutDirectoryNothingIsShown() {
        DirectoryController controller = controller(false);

        assertThat(controller.directory().enabled()).isFalse();
        assertThatThrownBy(() -> controller.enrollmentRequest(null)).isInstanceOf(NoSuchElementException.class);
        assertThat(controller.notFound(new NoSuchElementException("x")).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void enrollmentRequestIsSignedByTheBox() {
        var response = controller(true).enrollmentRequest(null);
        var named = controller(true).enrollmentRequest("CHU Exemple — site A");

        EnrollmentRequest request = EnrollmentRequest.verify(SignedDocument.fromJson(response.getBody()));
        assertThat(request.boxId()).isEqualTo(me.boxId());
        assertThat(request.transport()).containsEntry("iu", "simplex:/contact#box-a");
        assertThat(request.encryptionKeys()).isEqualTo(me.encryption().publicKey().encoded());
        transport.noAddress = true;
        assertThat(EnrollmentRequest.verify(SignedDocument.fromJson(controller(true).enrollmentRequest(null).getBody()))
                .transport()).isEmpty();
        assertThat(request.displayName()).isEqualTo("box-a");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("demande-" + me.boxId());
        assertThat(EnrollmentRequest.verify(SignedDocument.fromJson(named.getBody())).displayName())
                .isEqualTo("CHU Exemple — site A");
        assertThat(controller(true).enrollmentRequest(" ").getBody()).contains("box-a");
    }

    @Test
    void peersShowTheirDirectoryStatus() {
        MemoryRepositories.Peers peers = new MemoryRepositories.Peers();
        peers.save(new Peer("box-b", 5, "box-b", NOW).boundTo(partner.boxId()));
        peers.save(new Peer("box-c", 6, "box-c", NOW));
        PeerController controller = new PeerController(new PairingService(peers, new DisabledTransport(), clock,
                Duration.ofHours(1)), Providers.of(LocalDirectory.class, directory()));

        assertThat(controller.peers()).extracting(PeerController.PeerView::name, PeerController.PeerView::directoryStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("box-b", BoxEntry.Status.ACTIVE),
                        org.assertj.core.groups.Tuple.tuple("box-c", null));
    }

    @Test
    void pairsWithAnActiveBoxOfTheOrganization() {
        DirectoryController controller = controller(true);

        controller.pair(partner.boxId(), new DirectoryController.PairRequest("site-b"));

        assertThat(transport.joined).containsExactly("simplex:/contact#b");
        peers.save(new Peer("site-b", 5, "Site B", NOW).boundTo(partner.boxId()));
        assertThat(controller.directory().boxes()).filteredOn(b -> b.boxId().equals(partner.boxId()))
                .extracting(DirectoryController.BoxView::pairedAs).containsExactly("site-b");
        assertThatThrownBy(() -> controller.pair(partner.boxId(), new DirectoryController.PairRequest("x")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("déjà appairée");
    }

    @Test
    void pairingRefusals() {
        DirectoryController controller = controller(true);

        assertThatThrownBy(() -> controller(false).pair(partner.boxId(), new DirectoryController.PairRequest("b")))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> controller.pair(UUID.randomUUID(), new DirectoryController.PairRequest("b")))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> controller.pair(me.boxId(), new DirectoryController.PairRequest("b")))
                .hasMessageContaining("cette box");
        assertThatThrownBy(() -> controller.pair(revoked.boxId(), new DirectoryController.PairRequest("b")))
                .hasMessageContaining("non active");
        assertThatThrownBy(() -> controller.pair(silent.boxId(), new DirectoryController.PairRequest("b")))
                .hasMessageContaining("sans adresse");
        assertThatThrownBy(() -> controller.pair(partner.boxId(), new DirectoryController.PairRequest("../x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(controller.conflict(new IllegalStateException("x")).getStatus()).isEqualTo(409);
        assertThat(controller.invalid(new IllegalArgumentException("x")).getStatus()).isEqualTo(400);
        assertThat(controller.transportUnavailable(new com.imagunity.transport.TransportException("x")).getStatus())
                .isEqualTo(502);
    }
}
