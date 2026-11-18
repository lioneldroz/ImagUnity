package com.imagunity.config;

import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.DirectoryException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Écoute du flux de réveil de l'annuaire (Server-Sent Events), ouvert par la box : à une demande d'essai qui la vise,
 * ou à une publication (entrée, révocation, configuration, reconnaissance), la box relit l'annuaire sans attendre sa
 * lecture périodique, qui reste en place. Le signal ne fait foi de rien : tout ce qui est lu ensuite est vérifié.
 * Le flux coupé est rouvert, après un délai qui double jusqu'à 1 minute (une box pas encore enrôlée est refusée,
 * puis acceptée dès son entrée publiée) ; un annuaire sans flux est réessayé toutes
 * les 15 minutes.
 */
final class DirectoryWakeUp implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(DirectoryWakeUp.class);
    static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    static final Duration LAST_RETRY = Duration.ofMinutes(1);
    static final Duration NO_STREAM = Duration.ofMinutes(15);

    private final DirectoryClient client;
    private final String self;
    private final Runnable wake;
    private final Consumer<Duration> sleep;
    private final AtomicBoolean announced = new AtomicBoolean();
    private final AtomicBoolean opened = new AtomicBoolean();

    DirectoryWakeUp(DirectoryClient client, UUID self, Runnable wake) {
        this(client, self, wake, d -> {
            try {
                Thread.sleep(d);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    DirectoryWakeUp(DirectoryClient client, UUID self, Runnable wake, Consumer<Duration> sleep) {
        this.client = client;
        this.self = self.toString();
        this.wake = wake;
        this.sleep = sleep;
    }

    /** Vrai si cet événement doit réveiller la box. */
    boolean concerns(String event, String data) {
        return event.equals("publication") || (event.equals("essai") && self.equals(data));
    }

    /** Une écoute du flux ; renvoie le délai avant la suivante (doublé à chaque échec depuis {@code retry}). */
    Duration listenOnce(Duration retry) {
        try {
            boolean stream = client.listen((event, data) -> {
                if (event.equals("ouvert") && opened.compareAndSet(false, true)) {
                    LOG.info("Flux d'événements de l'annuaire ouvert : réveil immédiat aux publications et aux "
                            + "demandes d'essai");
                }
                if (concerns(event, data)) {
                    wake.run();
                }
            });
            if (!stream) {
                if (announced.compareAndSet(false, true)) {
                    LOG.info("L'annuaire n'a pas de flux d'événements : lecture périodique seulement");
                }
                return NO_STREAM;
            }
            return FIRST_RETRY; // fin normale (flux fermé par l'annuaire) : rouvert tout de suite ou presque
        } catch (DirectoryException e) {
            LOG.debug("Flux d'événements de l'annuaire : {} ; nouvel essai dans {} s", e.getMessage(),
                    retry.toSeconds());
            Duration next = retry.multipliedBy(2);
            return next.compareTo(LAST_RETRY) > 0 ? LAST_RETRY : next;
        }
    }

    @Override
    public void run() {
        Duration delay = FIRST_RETRY;
        while (!Thread.currentThread().isInterrupted()) {
            delay = listenOnce(delay);
            sleep.accept(delay);
        }
    }
}
