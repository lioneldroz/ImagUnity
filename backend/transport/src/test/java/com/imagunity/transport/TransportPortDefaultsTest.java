package com.imagunity.transport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TransportPortDefaultsTest {

    @Test
    void contactAddressesAreOptional() {
        TransportPort transport = new DisabledTransport();

        assertThatThrownBy(transport::contactAddress).isInstanceOf(TransportException.class);
        assertThatThrownBy(() -> transport.acceptContactRequest(1)).isInstanceOf(TransportException.class);
    }
}
