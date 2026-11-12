package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.inbound.InboundTransfer;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferState;
import com.imagunity.orchestration.RetryRefusedException;
import com.imagunity.orchestration.SendOrchestrator;
import com.imagunity.packaging.PackagingConfig;
import com.imagunity.packaging.StudyPackager;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class TransferControllerTest {

    /** Transport qui accepte les fichiers. */
    private static final class Transport implements TransportPort {
        final List<Path> sent = new ArrayList<>();

        @Override
        public String contactAddress() {
            return "adresse";
        }

        @Override
        public void connect(String link) {
        }

        @Override
        public void sendMessage(PeerId peer, String text) {
        }

        @Override
        public long sendFile(PeerId peer, Path file) {
            sent.add(file);
            return sent.size();
        }

        @Override
        public void receiveFile(long fileId, Path target) {
        }

        @Override
        public void addListener(Consumer<TransportEvent> listener) {
        }

        @Override
        public void close() {
        }
    }

    @TempDir
    Path dir;

    private final Instant now = Instant.parse("2026-11-11T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final MemoryRepositories.Transfers transfers = new MemoryRepositories.Transfers();
    private final MemoryRepositories.Inbound inbound = new MemoryRepositories.Inbound();
    private final MemoryRepositories.Peers peers = new MemoryRepositories.Peers();
    private final Transport transport = new Transport();
    private final ConsoleEvents events = new ConsoleEvents(Runnable::run);
    private final TransferController controller = new TransferController(transfers, inbound, peers,
            new SendOrchestrator(transfers, peers, new StudyPackager(PackagingConfig.defaults()), transport,
                    Path.of("outbox"), Runnable::run, clock),
            events, new ConsoleProperties(Duration.ofMinutes(30)), clock);

    private Transfer transfer(String study, TransferState state, Instant updatedAt) {
        Transfer t = Transfer.received(study, "IU_TO_B", "box-b", 100, updatedAt);
        t = switch (state) {
            case RECEIVED -> t;
            case ANNOUNCED -> t.packaged("g", 1, updatedAt).moveTo(TransferState.UPLOADED, updatedAt)
                    .moveTo(TransferState.ANNOUNCED, updatedAt);
            case FAILED -> t.packaged("g", 1, updatedAt).failed("Échec chez le destinataire : PACS", updatedAt);
            default -> throw new IllegalArgumentException();
        };
        transfers.create(t);
        return t;
    }

    @Test
    void overviewCountsTransfersAndLateAcks() {
        transfer("2.25.1", TransferState.ANNOUNCED, now.minus(Duration.ofHours(1)));
        transfer("2.25.2", TransferState.ANNOUNCED, now.minus(Duration.ofMinutes(5)));
        transfer("2.25.3", TransferState.FAILED, now);
        inbound.saveTransfer(new InboundTransfer(UUID.randomUUID(), "box-a", "g", "2.25.9", 1, "{}",
                InboundTransfer.State.RESTORED, null, now, now, 4, true));
        peers.save(new Peer("box-b", 5, "box-b", now));

        TransferViews.Overview overview = controller.overview();

        assertThat(overview.outbound()).containsEntry(TransferState.ANNOUNCED, 2L).containsEntry(TransferState.FAILED, 1L);
        assertThat(overview.inbound()).containsEntry(InboundTransfer.State.RESTORED, 1L);
        assertThat(overview.ackOverdue()).isEqualTo(1);
        assertThat(overview.peers()).isEqualTo(1);
    }

    @Test
    void listsTransfersMostRecentFirst() {
        Transfer old = transfer("2.25.1", TransferState.ANNOUNCED, now.minus(Duration.ofHours(1)));
        Transfer recent = transfer("2.25.2", TransferState.RECEIVED, now);

        List<TransferViews.Outbound> list = controller.transfers(50);

        assertThat(list).extracting(TransferViews.Outbound::id).containsExactly(recent.id(), old.id());
        assertThat(list.get(1).ackOverdue()).isTrue();
        assertThat(list.get(0).ackOverdue()).isFalse();
        assertThat(controller.transfers(1)).hasSize(1);
    }

    @Test
    void limitIsBounded() {
        assertThatThrownBy(() -> controller.transfers(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.inboundTransfers(501)).isInstanceOf(IllegalArgumentException.class);
        assertThat(controller.invalidRequest(new IllegalArgumentException("limit")).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void detailShowsPartsWithoutBoxPaths() {
        Transfer t = transfer("2.25.1", TransferState.ANNOUNCED, now);
        transfers.saveParts(t.id(), List.of(
                new TransferPart(t.id(), 1, 2, Path.of("/data/outbox", t.id().toString(), "g-1-of-2.tar.zst"), 10,
                        "a".repeat(64), 4L, true),
                new TransferPart(t.id(), 2, 2, Path.of("/data/outbox", t.id().toString(), "g-2-of-2.tar.zst"), 20,
                        "b".repeat(64), null, false)));

        TransferViews.OutboundDetail detail = controller.transfer(t.id());

        assertThat(detail.transfer().id()).isEqualTo(t.id());
        assertThat(detail.parts()).containsExactly(
                new TransferViews.Part(1, 2, "g-1-of-2.tar.zst", 10, "a".repeat(64), true, true),
                new TransferViews.Part(2, 2, "g-2-of-2.tar.zst", 20, "b".repeat(64), false, false));
    }

    @Test
    void unknownTransferIsNotFound() {
        assertThatThrownBy(() -> controller.transfer(UUID.randomUUID())).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> controller.retry(UUID.randomUUID())).isInstanceOf(NoSuchElementException.class);
        assertThat(controller.notFound(new NoSuchElementException("x")).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void retryResendsTheKeptPackages() throws Exception {
        peers.save(new Peer("box-b", 5, "box-b", now));
        Transfer t = transfer("2.25.1", TransferState.FAILED, now);
        Path file = Files.writeString(dir.resolve("g-1-of-1.tar.zst"), "paquet");
        transfers.saveParts(t.id(), List.of(new TransferPart(t.id(), 1, 1, file, 6, "a".repeat(64), 3L, true)));

        TransferViews.Outbound retried = controller.retry(t.id());

        assertThat(retried.state()).as("paquets confiés de nouveau, transfert annoncé").isEqualTo(TransferState.ANNOUNCED);
        assertThat(retried.errorMessage()).isNull();
        assertThat(transport.sent).containsExactly(file);
    }

    @Test
    void retryRefusalIsAConflict() {
        Transfer t = transfer("2.25.1", TransferState.ANNOUNCED, now);

        assertThatThrownBy(() -> controller.retry(t.id())).isInstanceOf(RetryRefusedException.class);
        assertThat(controller.retryRefused(new RetryRefusedException("non")).getStatus())
                .isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(controller.busy(new TimeoutException()).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    void listsInboundTransfers() {
        InboundTransfer t = new InboundTransfer(UUID.randomUUID(), "box-a", "g", "2.25.9", 2, "{}",
                InboundTransfer.State.FAILED, "PACS injoignable", now, now);
        inbound.saveTransfer(t);

        assertThat(controller.inboundTransfers(50)).singleElement().satisfies(v -> {
            assertThat(v.transferId()).isEqualTo(t.transferId());
            assertThat(v.peerName()).isEqualTo("box-a");
            assertThat(v.state()).isEqualTo(InboundTransfer.State.FAILED);
            assertThat(v.errorMessage()).isEqualTo("PACS injoignable");
            assertThat(v.ackSent()).isFalse();
        });
    }

    @Test
    void eventsOpenAStream() throws IOException {
        assertThat(controller.events()).isNotNull();
    }
}
