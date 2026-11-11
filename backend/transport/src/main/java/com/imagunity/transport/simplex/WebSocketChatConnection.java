package com.imagunity.transport.simplex;

import com.imagunity.transport.TransportException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Connexion WebSocket au client simplex-chat ({@code simplex-chat -p <port>}),
 * avec le client HTTP du JDK.
 *
 * <p>simplex-chat n'écoute que sur localhost et son API n'est ni authentifiée ni
 * chiffrée : la box et le client SimpleX doivent tourner sur la même machine
 * (ou partager la même pile réseau en conteneur).
 */
final class WebSocketChatConnection implements ChatConnection {

    private final WebSocket webSocket;

    private WebSocketChatConnection(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    /**
     * Ouvre la connexion.
     *
     * @param uri       par exemple {@code ws://localhost:5225}
     * @param onMessage appelé pour chaque message texte complet reçu
     * @param onClosed  appelé une fois, à la fermeture ou sur erreur ({@code null} si fermeture normale)
     */
    static WebSocketChatConnection open(URI uri, Duration connectTimeout,
                                        Consumer<String> onMessage, Consumer<Throwable> onClosed) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        try {
            WebSocket ws = http.newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .buildAsync(uri, new Listener(onMessage, onClosed))
                    .get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return new WebSocketChatConnection(ws);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Connexion à simplex-chat interrompue : " + uri, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new TransportException("simplex-chat injoignable : " + uri, e);
        }
    }

    @Override
    public void send(String text) {
        // sendText ne doit pas être appelé avant la fin de l'envoi précédent : on sérialise.
        synchronized (this) {
            webSocket.sendText(text, true).join();
        }
    }

    @Override
    public void close() {
        if (!webSocket.isOutputClosed()) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(e -> null);
        }
    }

    /** Recompose les messages reçus en plusieurs fragments. */
    private static final class Listener implements WebSocket.Listener {

        private final Consumer<String> onMessage;
        private final Consumer<Throwable> onClosed;
        private final StringBuilder buffer = new StringBuilder();

        Listener(Consumer<String> onMessage, Consumer<Throwable> onClosed) {
            this.onMessage = onMessage;
            this.onClosed = onClosed;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                onMessage.accept(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onClosed.accept(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            onClosed.accept(error);
        }
    }
}
