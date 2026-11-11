package com.imagunity.transport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DisabledTransportTest {

    private final DisabledTransport transport = new DisabledTransport();

    @Test
    void everyOperationFailsExplicitly() {
        PeerId peer = new PeerId(1);
        assertThatThrownBy(transport::createInvitation).hasMessageContaining("désactivé");
        assertThatThrownBy(() -> transport.acceptInvitation("lien")).isInstanceOf(TransportException.class);
        assertThatThrownBy(() -> transport.sendMessage(peer, "x")).isInstanceOf(TransportException.class);
        assertThatThrownBy(() -> transport.sendFile(peer, Path.of("x"))).isInstanceOf(TransportException.class);
        assertThatThrownBy(() -> transport.receiveFile(1, Path.of("x"))).isInstanceOf(TransportException.class);
    }

    @Test
    void listenersAndCloseAreHarmless() {
        transport.addListener(e -> {
            throw new AssertionError("aucun événement attendu");
        });
        transport.close();
    }
}
