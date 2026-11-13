package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Réponses du relais lues avec une limite : un relais compromis n'épuise pas la mémoire de la box. */
class BoundedReadTest {

    @Test
    void aResponseLongerThanTheLimitIsAbandoned() throws IOException {
        assertThat(IuFileClient.bounded(new ByteArrayInputStream(new byte[16]), 16)).hasSize(16);
        assertThatThrownBy(() -> IuFileClient.bounded(new ByteArrayInputStream(new byte[17]), 16))
                .isInstanceOf(IOException.class).hasMessageContaining("trop longue");
    }
}
