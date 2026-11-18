package com.imagunity.directoryserver;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Flux de réveil des box (Server-Sent Events). Chaque box en ouvre un vers l'annuaire ; l'annuaire y signale, sans
 * rien affirmer, qu'il y a du neuf :
 * <ul>
 *   <li>{@code ouvert} : le flux est ouvert (la box le note dans son journal) ;</li>
 *   <li>{@code essai} (texte : identifiant de la box) : une demande d'essai de connexion vient d'être déposée ;</li>
 *   <li>{@code publication} (texte : {@code entrees}, {@code configurations} ou {@code reconnaissances}) : une
 *       entrée, une révocation, une configuration ou une reconnaissance vient d'être publiée.</li>
 * </ul>
 * La box relit alors l'annuaire et vérifie elle-même les documents signés : un signal faux ne fait qu'avancer une
 * lecture. Les changements sont repérés dans la base, toutes les secondes : plusieurs instances du service d'annuaire
 * réveillent ainsi toutes les box, quelle que soit l'instance qui a reçu la publication. Un battement toutes les 25 s
 * garde le flux ouvert à travers les proxys ; chaque flux est fermé au bout de 10 minutes, et la box le rouvre.
 */
@Component
public class DirectoryEvents implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(DirectoryEvents.class);
    static final Duration TIMEOUT = Duration.ofMinutes(10);
    static final Duration HEARTBEAT = Duration.ofSeconds(25);
    static final Duration POLL = Duration.ofSeconds(1);
    /** Plus de flux ouverts à la fois : au-delà, refus (503) ; une box sans flux garde sa lecture périodique. */
    static final int MAX_LISTENERS = 5000;

    /** Trop de flux ouverts. */
    static final class TooManyListeners extends RuntimeException {
        TooManyListeners() {
            super("Trop de flux d'événements ouverts : la box garde sa lecture périodique");
        }
    }

    private final JdbcClient jdbc;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final int maxListeners;
    private ScheduledExecutorService scheduler;
    private Instant entries;
    private Instant configurations;
    private Instant recognitions;
    private Instant tests;

    @org.springframework.beans.factory.annotation.Autowired
    public DirectoryEvents(DataSource dataSource) {
        this(dataSource, MAX_LISTENERS);
    }

    DirectoryEvents(DataSource dataSource, int maxListeners) {
        this.jdbc = JdbcClient.create(dataSource);
        this.maxListeners = maxListeners;
    }

    /** Ouvre un flux pour une box. */
    public SseEmitter subscribe() {
        if (emitters.size() >= maxListeners) {
            throw new TooManyListeners();
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("ouvert").data("flux de réveil"));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /** Une demande d'essai vient d'être déposée ici : signalée sans attendre le prochain relevé. */
    void testRequested(UUID boxId) {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.execute(this::poll);
        }
    }

    /** Nombre de flux ouverts. */
    int listeners() {
        return emitters.size();
    }

    /** Relève les changements dans la base depuis le relevé précédent et les signale. */
    synchronized void poll() {
        try {
            Instant e = latest("box_entry", "published_at");
            if (after(e, entries)) {
                send("publication", "entrees");
            }
            entries = max(entries, e);
            Instant c = latest("configuration_document", "published_at");
            if (after(c, configurations)) {
                send("publication", "configurations");
            }
            configurations = max(configurations, c);
            Instant r = latest("recognition", "published_at");
            if (after(r, recognitions)) {
                send("publication", "reconnaissances");
            }
            recognitions = max(recognitions, r);
            Instant since = tests;
            Instant newest = since;
            for (var row : jdbc.sql("SELECT box_id, received_at FROM test_request" + (since == null ? ""
                    : " WHERE received_at > ?")).params(since == null ? List.of() : List.of(utc(since)))
                    .query((rs, n) -> new Object[] {rs.getObject("box_id", UUID.class),
                            rs.getObject("received_at", OffsetDateTime.class).toInstant()}).list()) {
                if (since != null) {
                    send("essai", row[0].toString());
                }
                newest = max(newest, (Instant) row[1]);
            }
            tests = newest == null ? Instant.EPOCH : newest;
        } catch (RuntimeException ex) {
            LOG.debug("Relevé des changements pour les flux d'événements : {}", ex.getMessage());
        }
    }

    private Instant latest(String table, String column) {
        OffsetDateTime t = jdbc.sql("SELECT MAX(" + column + ") FROM " + table).query(OffsetDateTime.class)
                .optional().orElse(null);
        return t == null ? Instant.EPOCH : t.toInstant();
    }

    /** Vrai si {@code value} est plus récent que le relevé précédent (jamais au premier relevé). */
    private static boolean after(Instant value, Instant previous) {
        return previous != null && value.isAfter(previous);
    }

    private static Instant max(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private void send(String name, String data) {
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    void heartbeat() {
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().comment("battement"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    @Override
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("annuaire-evenements")
                .daemon().factory());
        scheduler.execute(this::poll); // état initial : rien n'est signalé de ce qui existe déjà
        scheduler.scheduleWithFixedDelay(this::poll, POLL.toMillis(), POLL.toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT.toMillis(), HEARTBEAT.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        // Fermés avant l'arrêt du serveur web, qui sinon attendrait leur fin : les box rouvriront leur flux.
        for (SseEmitter emitter : List.copyOf(emitters)) {
            emitter.complete();
        }
        emitters.clear();
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
