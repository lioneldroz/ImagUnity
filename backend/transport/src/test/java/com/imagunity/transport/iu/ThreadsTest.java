package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** Arrêt des fils d'arrière-plan : interrompus puis attendus. */
class ThreadsTest {

    @Test
    void interruptsAndWaitsEvenWhenTheCallerWasAlreadyInterrupted() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Thread sleeper = Thread.ofVirtual().start(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                try {
                    Thread.sleep(50); // nettoyage après interruption : doit être attendu
                } catch (InterruptedException ignored) {
                    // pas d'autre interruption attendue
                }
            }
        });
        started.await();
        Thread.currentThread().interrupt();
        Threads.stop(List.of(Thread.currentThread(), sleeper), Duration.ofSeconds(10));
        assertThat(Thread.interrupted()).as("interruption rétablie").isTrue();
        assertThat(sleeper.isAlive()).isFalse();
    }

    @Test
    void givesUpAfterTheTimeoutOrWhenInterruptedWhileWaiting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread stubborn = Thread.ofVirtual().start(() -> {
            while (release.getCount() > 0) {
                Thread.onSpinWait();
            }
        });
        Threads.stop(List.of(stubborn, stubborn), Duration.ofMillis(50));
        assertThat(stubborn.isAlive()).as("délai dépassé : abandonné").isTrue();
        assertThat(Thread.interrupted()).isFalse();

        Thread caller = Thread.currentThread();
        Thread interrupter = Thread.ofVirtual().start(() -> {
            while (caller.getState() != Thread.State.TIMED_WAITING) {
                Thread.onSpinWait(); // l'appelant attend dans stop()
            }
            caller.interrupt();
        });
        Threads.stop(List.of(stubborn), Duration.ofSeconds(30));
        assertThat(Thread.interrupted()).as("attente abrégée, interruption rétablie").isTrue();
        assertThat(stubborn.isAlive()).isTrue();
        release.countDown();
        stubborn.join();
        interrupter.join();
    }
}
