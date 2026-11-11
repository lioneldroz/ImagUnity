package com.imagunity.api;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Diffusion des changements aux consoles connectées (Server-Sent Events).
 *
 * <p>Chaque événement porte un nom ({@code transfer}, {@code inbound}) et la vue JSON de l'objet modifié.
 * La console s'en sert pour rafraîchir ses listes ; elle se reconnecte d'elle-même si le flux se coupe.
 *
 * <p>Les envois se font sur un fil dédié : une console lente ne ralentit jamais l'orchestrateur, qui publie.
 *
 * <p>À l'arrêt de la box, les flux sont fermés avant l'arrêt du serveur web, qui sinon attendrait leur fin
 * (arrêt gracieux) ; les consoles se reconnecteront au redémarrage.
 */
@Component
public class ConsoleEvents implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(ConsoleEvents.class.getName());

    /** Au-delà, le flux est fermé et la console se reconnecte : évite d'accumuler des flux morts. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Executor sender;
    private volatile boolean running;

    public ConsoleEvents() {
        this(Executors.newSingleThreadExecutor(Thread.ofPlatform().name("console-sse").daemon().factory()));
    }

    ConsoleEvents(Executor sender) {
        this.sender = sender;
    }

    /** Ouvre un flux pour une console. */
    public SseEmitter subscribe() {
        SseEmitter emitter = newEmitter();
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, "ready", "");
        return emitter;
    }

    SseEmitter newEmitter() {
        return new SseEmitter(TIMEOUT.toMillis());
    }

    /** Envoie un événement à toutes les consoles connectées ; les flux fermés sont oubliés. */
    public void publish(String name, Object data) {
        if (emitters.isEmpty()) {
            return;
        }
        sender.execute(() -> emitters.forEach(emitter -> send(emitter, name, data)));
    }

    @Override
    public void start() {
        running = true;
    }

    /** Ferme les flux ouverts : appelé avant l'arrêt du serveur web (phase la plus haute). */
    @Override
    public void stop() {
        running = false;
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    int subscribers() {
        return emitters.size();
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException e) {
            LOG.log(Level.DEBUG, "Flux de console fermé", e);
            emitters.remove(emitter);
        }
    }
}
