package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TestRequestTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private final HybridKeyPair keys = HybridKeyPair.generate();
    private final Organization org = new Organization("chu-exemple", keys);
    private final UUID box = UUID.randomUUID();

    @Test
    void onlyARecentRequestSignedByTheOrganisationIsBelieved() {
        SignedDocument signed = org.signTestRequest(box, NOW);
        TestRequest request = TestRequest.verify(signed, "chu-exemple", keys.publicKey(), NOW.plusSeconds(60));
        assertThat(request.boxId()).isEqualTo(box);
        assertThat(request.id()).hasSize(36);
        assertThat(TestRequest.verify(signed, "chu-exemple", keys.publicKey(), NOW.minus(TestRequest.CLOCK_SKEW))
                .requestedAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> TestRequest.verify(signed, "chu-exemple", HybridKeyPair.generate().publicKey(), NOW))
                .hasMessageContaining("non signée");
        assertThatThrownBy(() -> TestRequest.verify(signed, "autre", keys.publicKey(), NOW))
                .hasMessageContaining("autre organisation");
        assertThatThrownBy(() -> TestRequest.verify(signed, "chu-exemple", keys.publicKey(),
                NOW.plus(TestRequest.MAX_AGE).plusSeconds(1))).hasMessageContaining("trop ancienne");
        assertThatThrownBy(() -> TestRequest.verify(signed, "chu-exemple", keys.publicKey(),
                NOW.minus(Duration.ofMinutes(6)))).hasMessageContaining("futur");
        SignedDocument otherType = SignedDocument.sign(signed.payload().replace(TestRequest.TYPE, "autre"), keys);
        assertThatThrownBy(() -> TestRequest.verify(otherType, "chu-exemple", keys.publicKey(), NOW))
                .hasMessageContaining("demande d'essai attendue");
        SignedDocument badId = SignedDocument.sign(signed.payload().replace(request.id(), "x"), keys);
        assertThatThrownBy(() -> TestRequest.verify(badId, "chu-exemple", keys.publicKey(), NOW))
                .isInstanceOf(DirectoryException.class);
        assertThat(ConfigurationReport.of(box, 0, null, 0, null, NOW).withAnswered("y".repeat(80)).answered())
                .hasSize(64);
    }

    @Test
    void eventsAreReadLineByLine() throws Exception {
        List<String> seen = new ArrayList<>();
        String stream = ": ouvert\n\nevent: essai\ndata: " + box + "\n\nevent:publication\r\ndata:entrees\r\n\r\n"
                + "data: a\ndata: b\n\nretry: 5\n\n";
        DirectoryClient.readEvents(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                (e, d) -> seen.add(e + "=" + d));
        assertThat(seen).containsExactly("essai=" + box, "publication=entrees", "message=a\nb");
        assertThatThrownBy(() -> DirectoryClient.readEvents(new ByteArrayInputStream("x".repeat(5000)
                .getBytes(StandardCharsets.UTF_8)), (e, d) -> { })).hasMessageContaining("trop longue");
    }

    @Test
    void theEventStreamIsOpenedByTheBoxAndClosedWhenSilent() throws Exception {
        AtomicInteger status = new AtomicInteger(200);
        List<String> headers = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/events", exchange -> {
            headers.add(String.valueOf(exchange.getRequestHeaders().getFirst(SignedRead.READER)));
            if (status.get() != 200) {
                exchange.sendResponseHeaders(status.get(), -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("event: essai\ndata: " + box + "\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(5000); // puis silence : la box coupe le flux
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            DirectoryClient client = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress()
                    .getPort()), Duration.ofSeconds(2)).eventsIdle(Duration.ofMillis(500))
                    .readAs(new SignedRead(box.toString(), HybridKeyPair.generate()));
            List<String> seen = new ArrayList<>();
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.listen((e, d) -> seen.add(e + "=" + d)))
                    .isInstanceOf(DirectoryException.class).hasMessageContaining("coupé");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
            assertThat(seen).containsExactly("essai=" + box);
            assertThat(headers.getFirst()).isEqualTo(box.toString());
            status.set(404);
            assertThat(client.listen((e, d) -> { })).as("annuaire antérieur").isFalse();
            status.set(403);
            assertThatThrownBy(() -> client.listen((e, d) -> { })).hasMessageContaining("HTTP 403");
            DirectoryClient withAccount = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress()
                    .getPort()), Duration.ofSeconds(2)).account("lecteur:secret");
            assertThatThrownBy(() -> withAccount.listen((e, d) -> { })).hasMessageContaining("HTTP 403");
        } finally {
            server.stop(0);
        }
        assertThatThrownBy(() -> new DirectoryClient(URI.create("http://127.0.0.1:9"), Duration.ofSeconds(1))
                .listen((e, d) -> { })).isInstanceOf(DirectoryException.class);
    }
}
