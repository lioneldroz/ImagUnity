package com.imagunity.domain.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferTest {

    private final Instant t0 = Instant.parse("2026-11-11T12:00:00Z");
    private final Instant t1 = t0.plusSeconds(10);

    @Test
    void newTransferIsReceived() {
        Transfer transfer = Transfer.received("2.25.1", "IU_TO_B", "box-b", 500, t0);

        assertThat(transfer.state()).isEqualTo(TransferState.RECEIVED);
        assertThat(transfer.id()).isNotNull();
        assertThat(transfer.partCount()).isZero();
        assertThat(transfer.packageGroupId()).isNull();
        assertThat(transfer.createdAt()).isEqualTo(t0).isEqualTo(transfer.updatedAt());
    }

    @Test
    void packagingRecordsGroupAndParts() {
        Transfer packaged = Transfer.received("2.25.1", "IU_TO_B", "box-b", 500, t0).packaged("g", 3, t1);

        assertThat(packaged.state()).isEqualTo(TransferState.PACKAGED);
        assertThat(packaged.packageGroupId()).isEqualTo("g");
        assertThat(packaged.partCount()).isEqualTo(3);
        assertThat(packaged.updatedAt()).isEqualTo(t1);
        assertThat(packaged.createdAt()).isEqualTo(t0);
    }

    @Test
    void failureKeepsReasonAndRetryClearsIt() {
        Transfer failed = Transfer.received("2.25.1", "IU_TO_B", "box-b", 500, t0).failed("relais injoignable", t1);

        assertThat(failed.state()).isEqualTo(TransferState.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("relais injoignable");

        Transfer retried = failed.moveTo(TransferState.RECEIVED, t1);
        assertThat(retried.errorMessage()).isNull();
    }

    @Test
    void forbiddenTransitionIsRefused() {
        Transfer transfer = Transfer.received("2.25.1", "IU_TO_B", "box-b", 500, t0);

        assertThatThrownBy(() -> transfer.moveTo(TransferState.ANNOUNCED, t1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECEIVED")
                .hasMessageContaining("ANNOUNCED");
    }

    @Test
    void requiredFields() {
        UUID id = UUID.randomUUID();
        TransferState s = TransferState.RECEIVED;
        assertThatThrownBy(() -> new Transfer(null, "1", "A", "d", s, 0, null, 0, null, t0, t0))
                .hasMessage("id");
        assertThatThrownBy(() -> new Transfer(id, null, "A", "d", s, 0, null, 0, null, t0, t0))
                .hasMessage("studyInstanceUid");
        assertThatThrownBy(() -> new Transfer(id, "1", null, "d", s, 0, null, 0, null, t0, t0))
                .hasMessage("calledAet");
        assertThatThrownBy(() -> new Transfer(id, "1", "A", null, s, 0, null, 0, null, t0, t0))
                .hasMessage("destination");
        assertThatThrownBy(() -> new Transfer(id, "1", "A", "d", null, 0, null, 0, null, t0, t0))
                .hasMessage("state");
        assertThatThrownBy(() -> new Transfer(id, "1", "A", "d", s, 0, null, 0, null, null, t0))
                .hasMessage("createdAt");
        assertThatThrownBy(() -> new Transfer(id, "1", "A", "d", s, 0, null, 0, null, t0, null))
                .hasMessage("updatedAt");
    }

    @Test
    void partProgresses() {
        UUID id = UUID.randomUUID();
        TransferPart part = new TransferPart(id, 1, 2, Path.of("p"), 10, "a".repeat(64), null, false);

        TransferPart handed = part.handedOver(42);
        TransferPart uploaded = handed.markUploaded();
        assertThat(uploaded.reset()).isEqualTo(part);

        assertThat(handed.transportFileId()).isEqualTo(42);
        assertThat(handed.uploaded()).isFalse();
        assertThat(uploaded.uploaded()).isTrue();
        assertThat(uploaded.transportFileId()).isEqualTo(42);
    }

    @Test
    void partRequiredFields() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new TransferPart(null, 1, 1, Path.of("p"), 1, "x", null, false))
                .hasMessage("transferId");
        assertThatThrownBy(() -> new TransferPart(id, 1, 1, null, 1, "x", null, false)).hasMessage("file");
        assertThatThrownBy(() -> new TransferPart(id, 1, 1, Path.of("p"), 1, null, null, false)).hasMessage("sha256");
    }
}
