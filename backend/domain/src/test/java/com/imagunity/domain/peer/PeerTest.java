package com.imagunity.domain.peer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class PeerTest {

    @Test
    void acceptsValidPeer() {
        Peer peer = new Peer("box-b_2", 5, "Box B", Instant.now());

        assertThat(peer.name()).isEqualTo("box-b_2");
        assertThat(Peer.requireValidName("CHU-Lyon")).isEqualTo("CHU-Lyon");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "../x", "é"})
    void rejectsInvalidNames(String name) {
        assertThatThrownBy(() -> Peer.requireValidName(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLongName() {
        assertThatThrownBy(() -> Peer.requireValidName("x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullNameAndDate() {
        assertThatThrownBy(() -> Peer.requireValidName(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Peer("box-b", 5, null, null)).isInstanceOf(NullPointerException.class);
    }

    @org.junit.jupiter.api.Test
    void peerIsBoundToADirectoryBox() {
        Peer peer = new Peer("box-b", 5, "box-b", java.time.Instant.parse("2026-11-12T10:00:00Z"));
        java.util.UUID box = java.util.UUID.randomUUID();

        Peer bound = peer.boundTo(box);

        org.assertj.core.api.Assertions.assertThat(peer.boxId()).isNull();
        org.assertj.core.api.Assertions.assertThat(bound.boxId()).isEqualTo(box);
        org.assertj.core.api.Assertions.assertThat(bound.name()).isEqualTo("box-b");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> peer.boundTo(null))
                .isInstanceOf(NullPointerException.class);
    }
}
