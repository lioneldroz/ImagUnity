package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryTest {

    @Test
    void transientFailuresAreRetriedWithGrowingDelays() {
        List<Duration> slept = new ArrayList<>();
        Retry retry = new Retry(Duration.ofMinutes(1), Duration.ofSeconds(4), slept::add);
        int[] calls = {0};

        String result = retry.run("Essai", () -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new IOException("coupure");
            }
            if (calls[0] < 5) {
                throw new RelayRefusedException(503, "surcharge");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(slept).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(4));
    }

    @Test
    void definitiveRefusalsAndExhaustedPatienceStopTheRetries() {
        Retry retry = new Retry(Duration.ofSeconds(2), Duration.ofSeconds(1), d -> { });

        assertThatThrownBy(() -> retry.run("Dépôt", () -> {
            throw new RelayRefusedException(403, "box révoquée");
        })).isInstanceOf(RelayRefusedException.class);
        assertThatThrownBy(() -> retry.run("Dépôt", () -> {
            throw new IOException("relais injoignable");
        })).isInstanceOf(TransportException.class).hasMessageContaining("Dépôt impossible : relais injoignable");
        Retry interrupted = new Retry(Duration.ofSeconds(2), Duration.ofSeconds(1), d -> {
            throw new InterruptedException();
        });
        assertThatThrownBy(() -> interrupted.run("Dépôt", () -> {
            throw new IOException("x");
        })).hasMessageContaining("interrompu");
        assertThat(Thread.interrupted()).isTrue();
        assertThat(Retry.standard().patience()).isEqualTo(Duration.ofMinutes(15));
        assertThat(new RelayRefusedException(429, "x").retryable()).isTrue();
    }
}
