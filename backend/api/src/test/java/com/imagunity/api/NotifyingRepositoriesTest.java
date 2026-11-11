package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.inbound.InboundFile;
import com.imagunity.domain.inbound.InboundTransfer;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferState;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotifyingRepositoriesTest {

    /** Événements publiés, sans console. */
    static final class RecordingEvents extends ConsoleEvents {
        record Event(String name, Object data) {
        }

        final List<Event> published = new ArrayList<>();

        RecordingEvents() {
            super(Runnable::run);
        }

        @Override
        public void publish(String name, Object data) {
            published.add(new Event(name, data));
        }
    }

    private final Instant now = Instant.parse("2026-11-11T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final RecordingEvents events = new RecordingEvents();
    private final MemoryRepositories.Transfers transfers = new MemoryRepositories.Transfers();
    private final NotifyingTransferRepository repository = new NotifyingTransferRepository(transfers, events,
            new ConsoleProperties(Duration.ofMinutes(30)), clock);

    @Test
    void transferChangesArePublished() {
        Transfer transfer = Transfer.received("2.25.1", "IU_TO_B", "box-b", 10, now);
        repository.create(transfer);
        Transfer packaged = transfer.packaged("g", 1, now);
        repository.update(packaged);
        TransferPart part = new TransferPart(transfer.id(), 1, 1, Path.of("outbox", "g-1-of-1.tar.zst"), 10,
                "a".repeat(64), null, false);
        repository.saveParts(transfer.id(), List.of(part));
        repository.updatePart(part.handedOver(7));
        // Paquet d'un transfert disparu : rien à signaler.
        TransferPart orphan = new TransferPart(UUID.randomUUID(), 1, 1, Path.of("x"), 1, "b".repeat(64), null, false);
        transfers.saveParts(orphan.transferId(), List.of(orphan));
        repository.updatePart(orphan.handedOver(8));

        assertThat(events.published).extracting(RecordingEvents.Event::name)
                .containsExactly("transfer", "transfer", "transfer");
        assertThat(events.published.getLast().data()).isInstanceOfSatisfying(TransferViews.Outbound.class,
                v -> assertThat(v.state()).isEqualTo(TransferState.PACKAGED));
    }

    @Test
    void readsAreDelegated() {
        Transfer transfer = Transfer.received("2.25.1", "IU_TO_B", "box-b", 10, now);
        repository.create(transfer);
        TransferPart part = new TransferPart(transfer.id(), 1, 1, Path.of("p"), 1, "a".repeat(64), 9L, false);
        repository.saveParts(transfer.id(), List.of(part));

        assertThat(repository.find(transfer.id())).contains(transfer);
        assertThat(repository.findActive("2.25.1", "IU_TO_B")).contains(transfer);
        assertThat(repository.findByStates(Set.of(TransferState.RECEIVED))).containsExactly(transfer);
        assertThat(repository.findRecent(5)).containsExactly(transfer);
        assertThat(repository.countByState()).containsEntry(TransferState.RECEIVED, 1L);
        assertThat(repository.parts(transfer.id())).containsExactly(part);
        assertThat(repository.findPartByTransportFileId(9)).contains(part);
    }

    @Test
    void inboundTransferChangesArePublished() {
        MemoryRepositories.Inbound inbound = new MemoryRepositories.Inbound();
        NotifyingInboundRepository notifying = new NotifyingInboundRepository(inbound, events);
        InboundTransfer transfer = new InboundTransfer(UUID.randomUUID(), "box-a", "g", "2.25.1", 1, "{}",
                InboundTransfer.State.ANNOUNCED, null, now, now);
        InboundFile file = new InboundFile(3, "box-a", "g-1-of-1.tar.zst", 1, InboundFile.State.DOWNLOADING, null, now);

        notifying.saveTransfer(transfer);
        notifying.saveFile(file);

        assertThat(events.published).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("inbound");
            assertThat(e.data()).isEqualTo(TransferViews.Inbound.of(transfer));
        });
        assertThat(notifying.findFile(3)).contains(file);
        assertThat(notifying.findFiles("box-a", "g-1-of-1.tar.zst")).containsExactly(file);
        assertThat(notifying.findTransfer("box-a", transfer.transferId())).contains(transfer);
        assertThat(notifying.findTransferByGroup("box-a", "g")).contains(transfer);
        assertThat(notifying.findTransfers(InboundTransfer.State.ANNOUNCED)).containsExactly(transfer);
        assertThat(notifying.findRecentTransfers(5)).containsExactly(transfer);
        assertThat(notifying.countTransfersByState()).containsEntry(InboundTransfer.State.ANNOUNCED, 1L);
        assertThat(notifying.findAwaitingAck()).isEmpty();
    }

    @Test
    void consoleDefaults() {
        assertThat(new ConsoleProperties(null).ackOverdue()).isEqualTo(Duration.ofMinutes(30));
    }
}
