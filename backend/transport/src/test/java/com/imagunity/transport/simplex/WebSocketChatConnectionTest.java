package com.imagunity.transport.simplex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.java_websocket.WebSocket;
import org.java_websocket.enums.Opcode;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Connexion WebSocket réelle vers un faux simplex-chat local. */
class WebSocketChatConnectionTest {

    /** Serveur WebSocket minimal ; le comportement à la réception est réglable par test. */
    static final class FakeServer extends WebSocketServer {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        final CountDownLatch started = new CountDownLatch(1);
        volatile BiConsumer<WebSocket, String> onText = (conn, text) -> { };

        FakeServer(int port) {
            super(new InetSocketAddress("127.0.0.1", port));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            received.add(message);
            onText.accept(conn, message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
        }

        @Override
        public void onStart() {
            started.countDown();
        }
    }

    private FakeServer server;
    private URI uri;
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> closures = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        int port = freePort();
        server = new FakeServer(port);
        server.start();
        assertThat(server.started.await(5, TimeUnit.SECONDS)).isTrue();
        uri = URI.create("ws://127.0.0.1:" + port);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        server.stop(1000);
    }

    private WebSocketChatConnection open() {
        return WebSocketChatConnection.open(uri, Duration.ofSeconds(5), messages::add,
                error -> closures.add(error == null ? "normal" : "erreur"));
    }

    @Test
    void sendsAndReceivesTextMessages() throws Exception {
        server.onText = (conn, text) -> conn.send("écho:" + text);
        WebSocketChatConnection connection = open();

        connection.send("{\"corrId\":\"1\"}");

        assertThat(server.received.poll(5, TimeUnit.SECONDS)).isEqualTo("{\"corrId\":\"1\"}");
        assertThat(messages.poll(5, TimeUnit.SECONDS)).isEqualTo("écho:{\"corrId\":\"1\"}");
        connection.close();
    }

    @Test
    void reassemblesFragmentedMessages() throws Exception {
        server.onText = (conn, text) -> {
            conn.sendFragmentedFrame(Opcode.TEXT, ByteBuffer.wrap("{\"resp\":".getBytes(StandardCharsets.UTF_8)), false);
            // Java-WebSocket transforme lui-même les fragments suivants en trames de continuation.
            conn.sendFragmentedFrame(Opcode.TEXT, ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8)), false);
            conn.sendFragmentedFrame(Opcode.TEXT, ByteBuffer.wrap("}".getBytes(StandardCharsets.UTF_8)), true);
        };
        WebSocketChatConnection connection = open();

        connection.send("go");

        assertThat(messages.poll(5, TimeUnit.SECONDS)).isEqualTo("{\"resp\":{}}");
        connection.close();
    }

    @Test
    void reportsClosureByServer() throws Exception {
        server.onText = (conn, text) -> conn.close(1000, "fin");
        WebSocketChatConnection connection = open();

        connection.send("ferme");

        assertThat(closures.poll(5, TimeUnit.SECONDS)).isNotNull();
        connection.close(); // sans effet une fois fermée
    }

    @Test
    void closeIsIdempotent() {
        WebSocketChatConnection connection = open();

        connection.close();
        connection.close();
    }

    @Test
    void unreachableServerFails() throws Exception {
        URI nobody = URI.create("ws://127.0.0.1:" + freePort());

        assertThatThrownBy(() -> WebSocketChatConnection.open(nobody, Duration.ofSeconds(2), m -> { }, e -> { }))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("injoignable");
    }

    @Test
    void interruptedOpenFails() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(this::open)
                    .isInstanceOf(TransportException.class)
                    .hasMessageContaining("interrompue");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void clientConnectsAndCorrelatesOverRealWebSocket() {
        server.onText = (conn, text) -> {
            String corrId = text.replaceAll(".*\"corrId\":\"([^\"]+)\".*", "$1");
            conn.send("{\"corrId\":\"" + corrId + "\",\"resp\":{\"type\":\"activeUser\",\"user\":{\"userId\":9}}}");
        };

        try (SimplexChatClient client = SimplexChatClient.connect(uri, Duration.ofSeconds(5))) {
            assertThat(client.command("/user").path("user").path("userId").asLong()).isEqualTo(9);
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
