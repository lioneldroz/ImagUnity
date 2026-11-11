package com.imagunity.transport.simplex;

import com.imagunity.transport.TransportException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Client bas niveau de l'API WebSocket de simplex-chat.
 *
 * <p>Protocole : chaque commande part sous la forme {@code {"corrId": "...", "cmd": "..."}} ;
 * la réponse revient avec le même {@code corrId} dans {@code {"corrId": "...", "resp": {...}}}.
 * Les messages sans {@code corrId} sont des événements, transmis aux écouteurs.
 * Les types inconnus doivent être ignorés : simplex-chat en émet qui ne sont pas documentés.
 *
 * <p>Si la connexion tombe (client simplex-chat redémarré), le client se reconnecte de lui-même, avec une attente
 * qui double à chaque échec (de 1 s à 30 s), puis prévient ses {@link #addReconnectListener écouteurs de
 * reconnexion} : ce qui est arrivé entre-temps n'a été signalé à personne.
 */
public final class SimplexChatClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SimplexChatClient.class.getName());

    private final JsonMapper mapper;
    private final Duration commandTimeout;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, String> pendingCommands = new ConcurrentHashMap<>();
    private final List<Consumer<JsonNode>> eventListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong nextCorrId = new AtomicLong();
    private final List<Runnable> reconnectListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final Supplier<ChatConnection> connector;
    private final Duration firstPause;
    private final Sleeper sleeper;
    private volatile ChatConnection connection;
    private volatile boolean closed;

    static final Duration FIRST_PAUSE = Duration.ofSeconds(1);
    static final Duration MAX_PAUSE = Duration.ofSeconds(30);

    /** Attente entre deux tentatives de reconnexion ; remplaçable pour les tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Client sans reconnexion (connexion attachée par {@link #attach}). */
    SimplexChatClient(JsonMapper mapper, Duration commandTimeout) {
        this(mapper, commandTimeout, null, FIRST_PAUSE, Thread::sleep);
    }

    /**
     * @param connector ouvre une nouvelle connexion (rappelée après une coupure), ou {@code null} : pas de
     *                  reconnexion
     */
    SimplexChatClient(JsonMapper mapper, Duration commandTimeout, Supplier<ChatConnection> connector,
                      Duration firstPause, Sleeper sleeper) {
        this.mapper = mapper;
        this.commandTimeout = commandTimeout;
        this.connector = connector;
        this.firstPause = firstPause;
        this.sleeper = sleeper;
    }

    /**
     * Se connecte à un client simplex-chat lancé avec {@code -p <port>}.
     *
     * @param uri            par exemple {@code ws://localhost:5225}
     * @param commandTimeout délai maximal d'attente d'une réponse
     */
    public static SimplexChatClient connect(URI uri, Duration commandTimeout) {
        SimplexChatClient[] self = new SimplexChatClient[1];
        Supplier<ChatConnection> connector = () -> WebSocketChatConnection.open(uri, Duration.ofSeconds(10),
                text -> self[0].onMessage(text), error -> self[0].onClosed(error));
        SimplexChatClient client = new SimplexChatClient(JsonMapper.builder().build(), commandTimeout, connector,
                FIRST_PAUSE, Thread::sleep);
        self[0] = client;
        client.attach(connector.get());
        return client;
    }

    void attach(ChatConnection connection) {
        this.connection = connection;
    }

    JsonMapper mapper() {
        return mapper;
    }

    /** Abonne un écouteur appelé après chaque reconnexion réussie. */
    public void addReconnectListener(Runnable listener) {
        reconnectListeners.add(listener);
    }

    /** Abonne un écouteur aux événements (champ {@code resp} des messages sans corrId). */
    public void addEventListener(Consumer<JsonNode> listener) {
        eventListeners.add(listener);
    }

    /**
     * Envoie une commande et attend sa réponse.
     *
     * @return le champ {@code resp} de la réponse
     * @throws SimplexCommandException si simplex-chat renvoie {@code chatCmdError}
     * @throws TransportException      en cas de délai dépassé ou de connexion perdue
     */
    public JsonNode command(String cmd) {
        try {
            return commandAsync(cmd).get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Commande interrompue", e);
        } catch (TimeoutException e) {
            throw new TransportException("Pas de réponse de simplex-chat après " + commandTimeout, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TransportException te) {
                throw te;
            }
            throw new TransportException("Échec de la commande simplex-chat", e.getCause());
        }
    }

    /** Version asynchrone de {@link #command(String)}. */
    public CompletableFuture<JsonNode> commandAsync(String cmd) {
        ChatConnection conn = connection;
        if (conn == null) {
            return CompletableFuture.failedFuture(new TransportException("Client simplex-chat non connecté"));
        }
        String corrId = Long.toString(nextCorrId.incrementAndGet());
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        // Enregistrer avant d'envoyer : la réponse peut arriver avant le retour de send().
        pending.put(corrId, future);
        pendingCommands.put(corrId, cmd);
        future.whenComplete((r, e) -> {
            pending.remove(corrId);
            pendingCommands.remove(corrId);
        });

        ObjectNode message = mapper.createObjectNode().put("corrId", corrId).put("cmd", cmd);
        try {
            conn.send(mapper.writeValueAsString(message));
        } catch (RuntimeException e) {
            future.completeExceptionally(new TransportException("Envoi impossible vers simplex-chat", e));
        }
        return future;
    }

    /** Traite un message reçu de simplex-chat. */
    void onMessage(String text) {
        JsonNode message;
        try {
            message = mapper.readTree(text);
        } catch (JacksonException e) {
            LOG.log(Level.WARNING, "Message illisible de simplex-chat ignoré", e);
            return;
        }
        JsonNode resp = message.path("resp");
        if (resp.isMissingNode()) {
            LOG.log(Level.DEBUG, "Message sans champ resp ignoré");
            return;
        }
        String corrId = message.path("corrId").asString();
        if (!corrId.isEmpty()) {
            CompletableFuture<JsonNode> future = pending.get(corrId);
            if (future == null) {
                LOG.log(Level.DEBUG, "Réponse à une commande inconnue ou expirée : corrId {0}", corrId);
                return;
            }
            if ("chatCmdError".equals(resp.path("type").asString())) {
                future.completeExceptionally(
                        new SimplexCommandException(pendingCommands.getOrDefault(corrId, ""), resp.path("chatError")));
            } else {
                future.complete(resp);
            }
            return;
        }
        for (Consumer<JsonNode> listener : eventListeners) {
            try {
                listener.accept(resp);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Erreur dans un écouteur d'événements simplex-chat", e);
            }
        }
    }

    /** La connexion est fermée : toutes les commandes en attente échouent, puis reconnexion si elle est prévue. */
    void onClosed(Throwable error) {
        TransportException failure = new TransportException("Connexion à simplex-chat fermée", error);
        pending.values().forEach(f -> f.completeExceptionally(failure));
        if (closed || connector == null) {
            return;
        }
        LOG.log(Level.WARNING, "Connexion à simplex-chat perdue{0} : reconnexion",
                error == null ? "" : " (" + error.getMessage() + ")");
        if (reconnecting.compareAndSet(false, true)) {
            connection = null;
            Thread.ofVirtual().name("simplex-reconnexion").start(this::reconnect);
        }
    }

    /** Retente la connexion jusqu'au succès ou jusqu'à la fermeture du client. */
    void reconnect() {
        Duration pause = firstPause;
        try {
            while (!closed) {
                sleeper.sleep(pause);
                ChatConnection conn;
                try {
                    conn = connector.get();
                } catch (TransportException e) {
                    LOG.log(Level.DEBUG, "simplex-chat toujours injoignable : {0}", e.getMessage());
                    pause = pause.multipliedBy(2).compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : pause.multipliedBy(2);
                    continue;
                }
                if (closed) {
                    conn.close();
                    return;
                }
                attach(conn);
                reconnecting.set(false);
                LOG.log(Level.INFO, "Reconnecté à simplex-chat");
                for (Runnable listener : reconnectListeners) {
                    try {
                        listener.run();
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Erreur dans un écouteur de reconnexion", e);
                    }
                }
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            reconnecting.set(false);
        }
    }

    @Override
    public void close() {
        closed = true;
        ChatConnection conn = connection;
        if (conn != null) {
            conn.close();
        }
        onClosed(null);
    }
}
