package com.imagunity.domain.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.exam.StudyRestorer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboundTest {

    private final Instant t0 = Instant.parse("2026-11-11T12:00:00Z");
    private final Instant t1 = t0.plusSeconds(1);

    @Test
    void fileProgresses() {
        InboundFile offered = new InboundFile(7, "box-a", "g-1-of-1.tar.zst", 10, InboundFile.State.DOWNLOADING,
                null, t0);

        InboundFile downloaded = offered.downloaded(Path.of("inbox/x"), t1);
        InboundFile consumed = downloaded.with(InboundFile.State.CONSUMED, t1);

        assertThat(downloaded.state()).isEqualTo(InboundFile.State.DOWNLOADED);
        assertThat(downloaded.file()).isEqualTo(Path.of("inbox/x"));
        assertThat(consumed.state()).isEqualTo(InboundFile.State.CONSUMED);
        assertThat(consumed.file()).isEqualTo(Path.of("inbox/x"));
        assertThat(consumed.updatedAt()).isEqualTo(t1);
    }

    @Test
    void transferEndsRestoredOrFailed() {
        InboundTransfer announced = new InboundTransfer(UUID.randomUUID(), "box-a", "g", "2.25.1", 1, "{}",
                InboundTransfer.State.ANNOUNCED, null, t0, t0);

        InboundTransfer failed = announced.failed("empreinte différente", t1);
        InboundTransfer restored = announced.restored(4, t1);

        assertThat(failed.state()).isEqualTo(InboundTransfer.State.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("empreinte différente");
        assertThat(restored.state()).isEqualTo(InboundTransfer.State.RESTORED);
        assertThat(restored.errorMessage()).isNull();
        assertThat(restored.createdAt()).isEqualTo(t0);
        assertThat(restored.restoredInstances()).isEqualTo(4);
        assertThat(failed.restoredInstances()).isZero();
    }

    @Test
    void ackIsAwaitedOnceTheTransferEnds() {
        InboundTransfer announced = new InboundTransfer(UUID.randomUUID(), "box-a", "g", "2.25.1", 1, "{}",
                InboundTransfer.State.ANNOUNCED, null, t0, t0);
        InboundTransfer restored = announced.restored(2, t1);
        InboundTransfer failed = announced.failed("x", t1);

        assertThat(announced.awaitsAck()).isFalse();
        assertThat(restored.awaitsAck()).isTrue();
        assertThat(failed.awaitsAck()).isTrue();

        InboundTransfer acked = restored.acknowledged(t1.plusSeconds(1));
        assertThat(acked.awaitsAck()).isFalse();
        assertThat(acked.ackSent()).isTrue();
        assertThat(acked.state()).isEqualTo(InboundTransfer.State.RESTORED);
        assertThat(acked.restoredInstances()).isEqualTo(2);
        assertThat(failed.acknowledged(t1).errorMessage()).isEqualTo("x");
    }

    @Test
    void requiredFields() {
        UUID id = UUID.randomUUID();
        var s = InboundTransfer.State.ANNOUNCED;
        assertThatThrownBy(() -> new InboundTransfer(null, "p", "g", "1", 1, "{}", s, null, t0, t0)).hasMessage("transferId");
        assertThatThrownBy(() -> new InboundTransfer(id, null, "g", "1", 1, "{}", s, null, t0, t0)).hasMessage("peerName");
        assertThatThrownBy(() -> new InboundTransfer(id, "p", null, "1", 1, "{}", s, null, t0, t0)).hasMessage("packageGroupId");
        assertThatThrownBy(() -> new InboundTransfer(id, "p", "g", null, 1, "{}", s, null, t0, t0)).hasMessage("studyInstanceUid");
        assertThatThrownBy(() -> new InboundTransfer(id, "p", "g", "1", 1, null, s, null, t0, t0)).hasMessage("announcement");
        assertThatThrownBy(() -> new InboundTransfer(id, "p", "g", "1", 1, "{}", null, null, t0, t0)).hasMessage("state");
        assertThatThrownBy(() -> new InboundTransfer(id, "p", "g", "1", 1, "{}", s, null, null, t0)).hasMessage("createdAt");
        assertThatThrownBy(() -> new InboundTransfer(id, "p", "g", "1", 1, "{}", s, null, t0, null)).hasMessage("updatedAt");
        var d = InboundFile.State.DOWNLOADING;
        assertThatThrownBy(() -> new InboundFile(1, null, "f", 1, d, null, t0)).hasMessage("peerName");
        assertThatThrownBy(() -> new InboundFile(1, "p", null, 1, d, null, t0)).hasMessage("fileName");
        assertThatThrownBy(() -> new InboundFile(1, "p", "f", 1, null, null, t0)).hasMessage("state");
        assertThatThrownBy(() -> new InboundFile(1, "p", "f", 1, d, null, null)).hasMessage("updatedAt");
    }

    @Test
    void restorerInstanceCarriesExpectedIdentifiers() {
        var instance = new StudyRestorer.Instance("2.25.1.1", "2.25.1.1.1", Path.of("x.dcm"));

        assertThat(instance.sopInstanceUid()).isEqualTo("2.25.1.1.1");
    }
}
