package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.Organization;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Demandes d'essai lues par la box, et réveil par le flux d'événements de l'annuaire (serveur HTTP de test). */
class TestRequestsAndWakeUpTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private final HybridKeyPair orgKeys = HybridKeyPair.generate();
    private final Organization org = new Organization("chu-exemple", orgKeys);
    private final UUID self = UUID.randomUUID();
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicInteger requestStatus = new AtomicInteger(200);
    private final AtomicInteger eventsStatus = new AtomicInteger(200);
    private final AtomicReference<String> stream = new AtomicReference<>("");
    private HttpServer server;
    private DirectoryClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/test-requests/" + self, exchange -> {
            String body = request.get();
            int status = body == null ? 404 : requestStatus.get();
            byte[] bytes = body == null || status != 200 ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.createContext("/directory/v1/events", exchange -> {
            if (eventsStatus.get() != 200) {
                exchange.sendResponseHeaders(eventsStatus.get(), -1);
                exchange.close();
                return;
            }
            byte[] bytes = stream.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aSignedRequestForThisBoxIsFollowedOnce() {
        TestRequests tests = new TestRequests(self, "chu-exemple", orgKeys.publicKey(), Clock.fixed(NOW,
                ZoneOffset.UTC));
        assertThat(tests.next(client)).as("aucune demande").isEmpty();
        request.set(org.signTestRequest(self, NOW.minusSeconds(30)).toJson());
        String id = tests.next(client).orElseThrow();
        assertThat(id).hasSize(36);
        assertThat(tests.next(client)).as("déjà traitée").isEmpty();
        request.set(new Organization("chu-exemple", HybridKeyPair.generate()).signTestRequest(self, NOW).toJson());
        assertThat(tests.next(client)).as("signature d'une autre clé").isEmpty();
        request.set(org.signTestRequest(self, NOW.minus(Duration.ofHours(1))).toJson());
        assertThat(tests.next(client)).as("trop ancienne").isEmpty();
        request.set(org.signTestRequest(UUID.randomUUID(), NOW).toJson());
        assertThat(tests.next(client)).as("pour une autre box").isEmpty();
        request.set(org.signTestRequest(self, NOW).toJson());
        requestStatus.set(500);
        assertThat(tests.next(client)).as("annuaire en erreur").isEmpty();
        requestStatus.set(200);
        assertThat(tests.next(client)).as("nouvelle demande").isPresent();
    }

    @Test
    void theBoxWakesUpOnItsOwnRequestsAndOnPublications() {
        stream.set("event: ouvert\ndata: flux\n\nevent: essai\ndata: " + UUID.randomUUID() + "\n\nevent: essai\ndata: " + self
                + "\n\nevent: publication\ndata: entrees\n\n: battement\n\n");
        AtomicInteger wakes = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        DirectoryWakeUp wakeUp = new DirectoryWakeUp(client, self, wakes::incrementAndGet, sleeps::add);
        assertThat(wakeUp.listenOnce(DirectoryWakeUp.FIRST_RETRY)).isEqualTo(DirectoryWakeUp.FIRST_RETRY);
        assertThat(wakes.get()).as("sa demande et la publication, pas la demande d'une autre box").isEqualTo(2);

        eventsStatus.set(503);
        assertThat(wakeUp.listenOnce(DirectoryWakeUp.FIRST_RETRY)).isEqualTo(Duration.ofSeconds(10));
        assertThat(wakeUp.listenOnce(Duration.ofSeconds(40))).as("plafond").isEqualTo(DirectoryWakeUp.LAST_RETRY);
        eventsStatus.set(404);
        assertThat(wakeUp.listenOnce(DirectoryWakeUp.FIRST_RETRY)).as("annuaire sans flux")
                .isEqualTo(DirectoryWakeUp.NO_STREAM);
        assertThat(wakeUp.listenOnce(DirectoryWakeUp.FIRST_RETRY)).isEqualTo(DirectoryWakeUp.NO_STREAM);

        Thread runner = new Thread(() -> new DirectoryWakeUp(client, self, () -> { }, d -> {
            sleeps.add(d);
            Thread.currentThread().interrupt();
        }).run());
        runner.start();
        assertThat(runner).satisfies(t -> {
            t.join(5000);
            assertThat(t.isAlive()).isFalse();
        });
        assertThat(sleeps).containsExactly(DirectoryWakeUp.NO_STREAM);
        new DirectoryWakeUp(client, self, () -> { }); // délai réel, par défaut
    }
}
