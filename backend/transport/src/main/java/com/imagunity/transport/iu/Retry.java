package com.imagunity.transport.iu;

import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.time.Duration;

/**
 * Nouvelles tentatives d'un appel au relais : coupure réseau, relais surchargé ou en panne, morceau pas encore prêt.
 * Attente croissante (1, 2, 4… s, au plus {@code maxDelay}) jusqu'à {@code patience} ; un refus définitif (4xx) n'est
 * pas réessayé.
 *
 * @param patience durée totale d'attente admise pour un même appel
 * @param maxDelay attente maximale entre deux essais
 * @param sleeper  attente (remplaçable dans les tests)
 */
public record Retry(Duration patience, Duration maxDelay, Sleeper sleeper) {

    /** Attente entre deux essais. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Appel au relais. */
    @FunctionalInterface
    public interface Call<T> {
        T call() throws IOException;
    }

    /** Par défaut : 15 minutes de patience, 30 s au plus entre deux essais. */
    public static Retry standard() {
        return new Retry(Duration.ofMinutes(15), Duration.ofSeconds(30), d -> Thread.sleep(d));
    }

    public <T> T run(String what, Call<T> call) {
        Duration waited = Duration.ZERO;
        Duration delay = Duration.ofSeconds(1);
        while (true) {
            Exception failure;
            try {
                return call.call();
            } catch (IOException e) {
                failure = e;
            } catch (RelayRefusedException e) {
                if (!e.retryable()) {
                    throw e;
                }
                failure = e;
            }
            if (waited.compareTo(patience) >= 0) {
                throw new TransportException(what + " impossible : " + failure.getMessage(), failure);
            }
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransportException(what + " interrompu", e);
            }
            waited = waited.plus(delay);
            delay = delay.multipliedBy(2).compareTo(maxDelay) > 0 ? maxDelay : delay.multipliedBy(2);
        }
    }
}
