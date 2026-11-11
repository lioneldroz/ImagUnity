package com.imagunity.domain.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransferStateTest {

    @Test
    void nominalPathIsAllowed() {
        assertThat(TransferState.RECEIVED.canTransitionTo(TransferState.PACKAGED)).isTrue();
        assertThat(TransferState.PACKAGED.canTransitionTo(TransferState.UPLOADED)).isTrue();
        assertThat(TransferState.UPLOADED.canTransitionTo(TransferState.ANNOUNCED)).isTrue();
        assertThat(TransferState.ANNOUNCED.canTransitionTo(TransferState.ACKNOWLEDGED)).isTrue();
    }

    @Test
    void stepsCannotBeSkipped() {
        assertThat(TransferState.RECEIVED.canTransitionTo(TransferState.ANNOUNCED)).isFalse();
        assertThat(TransferState.PACKAGED.canTransitionTo(TransferState.ACKNOWLEDGED)).isFalse();
    }

    @Test
    void acknowledgedIsFinal() {
        assertThat(TransferState.ACKNOWLEDGED.isTerminal()).isTrue();
        for (TransferState next : TransferState.values()) {
            assertThat(TransferState.ACKNOWLEDGED.canTransitionTo(next)).isFalse();
        }
    }

    @Test
    void onlyAcknowledgedAndFailedAreTerminal() {
        assertThat(TransferState.values())
                .filteredOn(TransferState::isTerminal)
                .containsExactlyInAnyOrder(TransferState.ACKNOWLEDGED, TransferState.FAILED);
    }

    @Test
    void everyActiveStateCanFail() {
        for (TransferState state : TransferState.values()) {
            if (!state.isTerminal()) {
                assertThat(state.canTransitionTo(TransferState.FAILED)).as(state.name()).isTrue();
            }
        }
    }

    @Test
    void failedTransferCanBeResumed() {
        assertThat(TransferState.FAILED.canTransitionTo(TransferState.UPLOADED)).isTrue();
        assertThat(TransferState.FAILED.canTransitionTo(TransferState.ACKNOWLEDGED)).isFalse();
    }
}
