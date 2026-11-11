package com.imagunity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.inbound.InboundFile;
import com.imagunity.domain.inbound.InboundTransfer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcInboundRepositoryTest {

    private final JdbcInboundRepository repository = new JdbcInboundRepository(TestDatabase.migrated());
    private final Instant t0 = Instant.parse("2026-11-11T12:00:00Z");

    private InboundTransfer announced(String peer, String group) {
        return new InboundTransfer(UUID.randomUUID(), peer, group, "2.25.1", 2, "{\"type\":\"x\"}",
                InboundTransfer.State.ANNOUNCED, null, t0, t0);
    }

    @Test
    void savesAndUpdatesFiles() {
        InboundFile downloading = new InboundFile(7, "box-a", "g-1-of-1.tar.zst", 100, InboundFile.State.DOWNLOADING,
                null, t0);
        repository.saveFile(downloading);
        assertThat(repository.findFile(7)).contains(downloading);

        InboundFile downloaded = downloading.downloaded(Path.of("inbox", "7-g-1-of-1.tar.zst"), t0.plusSeconds(5));
        repository.saveFile(downloaded);

        assertThat(repository.findFile(7)).contains(downloaded);
        assertThat(repository.findFile(8)).isEmpty();
    }

    @Test
    void findsFilesByPeerAndNameMostRecentFirst() {
        repository.saveFile(new InboundFile(1, "box-a", "g-1-of-1.tar.zst", 1, InboundFile.State.FAILED, null, t0));
        repository.saveFile(new InboundFile(2, "box-a", "g-1-of-1.tar.zst", 1, InboundFile.State.DOWNLOADED,
                Path.of("x"), t0.plusSeconds(1)));
        repository.saveFile(new InboundFile(3, "box-c", "g-1-of-1.tar.zst", 1, InboundFile.State.DOWNLOADED,
                Path.of("y"), t0));

        assertThat(repository.findFiles("box-a", "g-1-of-1.tar.zst"))
                .extracting(InboundFile::transportFileId).containsExactly(2L, 1L);
        assertThat(repository.findFiles("box-a", "autre")).isEmpty();
    }

    @Test
    void savesAndFindsTransfers() {
        InboundTransfer transfer = announced("box-a", "g1");
        repository.saveTransfer(transfer);
        repository.saveTransfer(announced("box-c", "g2"));

        assertThat(repository.findTransfer("box-a", transfer.transferId())).contains(transfer);
        assertThat(repository.findTransfer("box-c", transfer.transferId())).isEmpty();
        assertThat(repository.findTransferByGroup("box-a", "g1")).contains(transfer);
        assertThat(repository.findTransferByGroup("box-a", "g2")).isEmpty();
        assertThat(repository.findTransfers(InboundTransfer.State.ANNOUNCED)).hasSize(2);
    }

    @Test
    void updatesTransferState() {
        InboundTransfer transfer = announced("box-a", "g1");
        repository.saveTransfer(transfer);

        repository.saveTransfer(transfer.failed("empreinte différente", t0.plusSeconds(3)));

        InboundTransfer read = repository.findTransfer("box-a", transfer.transferId()).orElseThrow();
        assertThat(read.state()).isEqualTo(InboundTransfer.State.FAILED);
        assertThat(read.errorMessage()).isEqualTo("empreinte différente");
        assertThat(read.announcement()).isEqualTo("{\"type\":\"x\"}");
        assertThat(repository.findTransfers(InboundTransfer.State.ANNOUNCED)).isEmpty();
        assertThat(repository.findTransfers(InboundTransfer.State.FAILED)).hasSize(1);
    }
}
