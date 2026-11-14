package com.imagunity.transport.iu;

import java.time.Duration;
import java.util.Collection;

/** Arrêt des fils d'arrière-plan : interrompus, puis attendus pour qu'aucun n'écrive plus dans l'état de la box. */
final class Threads {

    /** Attente maximale d'un arrêt : un fil bloqué ne retient pas indéfiniment la fermeture. */
    static final Duration STOP_WAIT = Duration.ofSeconds(10);

    private Threads() {
    }

    /**
     * Interrompt les fils puis attend leur fin, au plus {@code timeout} en tout. Le fil appelant n'est ni interrompu ni
     * attendu. Une interruption de l'appelant déjà posée n'empêche pas l'attente ; une interruption pendant l'attente
     * l'abrège. Dans les deux cas, elle est rétablie au retour.
     */
    static void stop(Collection<Thread> threads, Duration timeout) {
        Thread self = Thread.currentThread();
        for (Thread thread : threads) {
            if (thread != self) {
                thread.interrupt();
            }
        }
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            for (Thread thread : threads) {
                long left = deadline - System.nanoTime();
                if (thread != self && left > 0) {
                    thread.join(Duration.ofNanos(left));
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) {
            self.interrupt();
        }
    }
}
