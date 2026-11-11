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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
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
 */
public final class SimplexChatClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SimplexChatClient.class.getName());

    private final JsonMapper mapper;
    private final Duration commandTimeout;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, String> pendingCommands = new ConcurrentHashMap<>();
    private final List<Consumer<JsonNode>> eventListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong nextCorrId = new AtomicLong();
    private volatile ChatConnection connection;

    SimplexChatClient(JsonMapper mapper, Duration commandTimeout) {
        this.mapper = mapper;
        this.commandTimeout = commandTimeout;
    }

    /**
     * Se connecte à un client simplex-chat lancé avec {@code -p <port>}.
     *
     * @param uri            par exemple {@code ws://localhost:5225}
     * @param commandTimeout délai maximal d'attente d'une réponse
     */
    public static SimplexChatClient connect(URI uri, Duration commandTimeout) {
        SimplexChatClient client = new SimplexChatClient(JsonMapper.builder().build(), commandTimeout);
        client.attach(WebSocketChatConnection.open(uri, Duration.ofSeconds(10), client::onMessage, client::onClosed));
        return client;
    }

    void attach(ChatConnection connection) {
        this.connection = connection;
    }

    JsonMapper mapper() {
        return mapper;
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

    /** La connexion est fermée : toutes les commandes en attente échouent. */
    void onClosed(Throwable error) {
        TransportException failure = new TransportException("Connexion à simplex-chat fermée", error);
        pending.values().forEach(f -> f.completeExceptionally(failure));
        if (error != null) {
            LOG.log(Level.WARNING, "Connexion à simplex-chat perdue", error);
        }
    }

    @Override
    public void close() {
        ChatConnection conn = connection;
        if (conn != null) {
            conn.close();
        }
        onClosed(null);
    }
}
