package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalDirectoryTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private static final Organization ORG = new Organization("chu-exemple", ORG_KEYS);
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    /** Cache en mémoire. */
    static final class MemoryCache implements EntryCache {
        final Map<UUID, SignedDocument> documents = new LinkedHashMap<>();

        @Override
        public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
            documents.put(boxId, document);
        }

        @Override
        public List<SignedDocument> entries() {
            return new ArrayList<>(documents.values());
        }
    }

    private final MemoryCache cache = new MemoryCache();
    private final SerialMemory serials = SerialMemory.inMemory();
    private final Clock clock = Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC);

    private LocalDirectory directory() {
        return new LocalDirectory(new EntryVerifier("chu-exemple", ORG_KEYS.publicKey(), serials, clock), cache, clock);
    }

    private static SignedDocument enrolled(UUID box) {
        return ORG.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), box, "Box " + box, Map.of(), NOW), NOW,
                Duration.ofDays(30), 1);
    }

    @Test
    void synchronizesVerifiedEntriesAndRejectsTheOthers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        SignedDocument forged = new Organization("chu-exemple", HybridKeyPair.generate()).enroll(
                EnrollmentRequest.create(HybridKeyPair.generate(), UUID.randomUUID(), "Intrus", Map.of(), NOW), NOW,
                Duration.ofDays(30), 1);
        LocalDirectory directory = directory();

        LocalDirectory.SyncStatus status = directory.synchronize(() -> List.of(enrolled(a), enrolled(b), forged));

        assertThat(status.accepted()).isEqualTo(2);
        assertThat(status.rejected()).isEqualTo(1);
        assertThat(status.error()).isNull();
        assertThat(status.at()).isEqualTo(NOW.plusSeconds(60));
        assertThat(directory.entries()).hasSize(2);
        assertThat(directory.status(a)).isEqualTo(BoxEntry.Status.ACTIVE);
        assertThat(directory.status(UUID.randomUUID())).isNull();
        assertThat(cache.documents).containsKeys(a, b);
    }

    @Test
    void revocationIsKeptAndRollbackRejected() {
        UUID a = UUID.randomUUID();
        SignedDocument entry = enrolled(a);
        SignedDocument revoked = ORG.revoke(entry, NOW, "Box volée");
        LocalDirectory directory = directory();
        directory.synchronize(() -> List.of(revoked));

        LocalDirectory.SyncStatus rollback = directory.synchronize(() -> List.of(entry));

        assertThat(rollback.rejected()).isEqualTo(1);
        assertThat(directory.status(a)).isEqualTo(BoxEntry.Status.REVOKED);
    }

    @Test
    void boxesMissingFromALaterReadKeepTheirLastEntry() {
        UUID a = UUID.randomUUID();
        LocalDirectory directory = directory();
        directory.synchronize(() -> List.of(enrolled(a)));

        directory.synchronize(List::of);

        assertThat(directory.entry(a)).isPresent();
    }

    @Test
    void unreachableDirectoryIsOnlyNoted() {
        UUID a = UUID.randomUUID();
        LocalDirectory directory = directory();
        directory.synchronize(() -> List.of(enrolled(a)));

        LocalDirectory.SyncStatus status = directory.synchronize(() -> {
            throw new DirectoryException("Annuaire injoignable : refus de connexion");
        });

        assertThat(status.error()).contains("injoignable");
        assertThat(directory.lastSync()).isEqualTo(status);
        assertThat(directory.entry(a)).isPresent();
    }

    @Test
    void cachedEntriesAreReloadedAndReverified() {
        UUID a = UUID.randomUUID();
        directory().synchronize(() -> List.of(enrolled(a)));
        cache.documents.put(UUID.randomUUID(), new SignedDocument("{}", new HybridSignature("IU1", "x", "y")));

        LocalDirectory restarted = directory();
        restarted.load();

        assertThat(restarted.entries()).extracting(BoxEntry::boxId).containsExactly(a);
        assertThat(restarted.lastSync().at()).isNull();
    }

    @Test
    void clientReadsThePublishedEntries() throws Exception {
        SignedDocument entry = enrolled(UUID.randomUUID());
        int[] status = {200};
        String[] body = {"{\"organization\":\"chu-exemple\",\"entries\":[" + entry.toJson() + "]}"};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/entries", exchange -> {
            byte[] bytes = body[0].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status[0], bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        DirectoryClient client = new DirectoryClient(url, Duration.ofSeconds(5));
        try {
            assertThat(client.fetch()).containsExactly(entry);
            body[0] = "{\"organization\":\"chu-exemple\",\"entries\":null}";
            assertThat(client.fetch()).isEmpty();
            status[0] = 503;
            assertThatThrownBy(client::fetch).isInstanceOf(DirectoryException.class).hasMessageContaining("503");
        } finally {
            server.stop(0);
        }
        assertThatThrownBy(client::fetch).isInstanceOf(DirectoryException.class).hasMessageContaining("injoignable");
    }

    @Test
    void clientReadsConfigurationsAndSendsReports() throws Exception {
        SignedDocument document = SignedDocument.sign("{}", HybridKeyPair.generate());
        int[] status = {200};
        String[] body = {"{\"organization\":\"chu-exemple\",\"configurations\":[" + document.toJson() + "]}"};
        java.util.List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/configurations", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                reports.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(status[0] == 200 ? 201 : status[0], -1);
            } else {
                byte[] bytes = body[0].getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status[0], bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        DirectoryClient client = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5));
        try {
            assertThat(client.fetchConfigurations()).containsExactly(document);
            body[0] = "{\"organization\":\"chu-exemple\",\"configurations\":null}";
            assertThat(client.fetchConfigurations()).isEmpty();
            client.report(document);
            assertThat(reports).singleElement().satisfies(r -> assertThat(r).contains("payload"));
            status[0] = 422;
            assertThatThrownBy(client::fetchConfigurations).hasMessageContaining("422");
            assertThatThrownBy(() -> client.report(document)).hasMessageContaining("refusé");
        } finally {
            server.stop(0);
        }
        assertThatThrownBy(client::fetchConfigurations).hasMessageContaining("injoignable");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.report(document)).hasMessageContaining("interrompu");
        } finally {
            Thread.interrupted();
        }
    }
}
