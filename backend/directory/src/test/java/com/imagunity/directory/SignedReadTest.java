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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Étape 4 de la fédération : lectures signées, lecture par différence, repli d'une box pas encore enrôlée. */
class SignedReadTest {

    private static final HybridKeyPair KEYS = HybridKeyPair.generate();
    private static final Organization A = new Organization("grand-est", HybridKeyPair.generate());
    private static final Organization B = new Organization("bourgogne-fc", HybridKeyPair.generate());
    private static final Instant NOW = Instant.parse("2026-12-01T10:00:00Z");

    /** Une requête reçue : chemin et paramètres, en-têtes de lecture et d'autorisation. */
    private record Seen(String pathAndQuery, String reader, String authorization) {
    }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int listStatus = 200;
    private volatile int ownStatus = 200;
    private volatile String listBody = "{\"organization\":\"grand-est\",\"entries\":[],\"cursor\":7}";
    private volatile String ownBody;
    private HttpServer server;
    private URI url;
    private Instant now = NOW;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/", exchange -> {
            URI uri = exchange.getRequestURI();
            String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            seen.add(new Seen(pathAndQuery, exchange.getRequestHeaders().getFirst(SignedRead.READER),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            boolean own = uri.getPath().matches("/directory/v1/entries/[0-9a-f-]+");
            int status = own ? ownStatus : listStatus;
            String body = own ? ownBody : uri.getPath().endsWith("/recognitions")
                    ? "{\"organization\":\"grand-est\",\"recognitions\":[]}" : uri.getPath().endsWith("/configurations")
                    ? "{\"organization\":\"grand-est\",\"configurations\":[]}" : listBody;
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
            if (status == 200) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aReadIsSignedForItsPathAndTimeOnly() {
        SignedRead read = new SignedRead("box", KEYS);
        Map<String, String> h = read.headers("/directory/v1/entries?since=3", NOW);
        assertThat(h).containsEntry(SignedRead.READER, "box").containsKey(SignedRead.SIGNATURE);
        String t = h.get(SignedRead.TIMESTAMP);
        String s = h.get(SignedRead.SIGNATURE);
        assertThat(SignedRead.verify("box", "/directory/v1/entries?since=3", t, s, KEYS.publicKey(), NOW)).isTrue();
        assertThat(SignedRead.verify("box", "/directory/v1/entries?since=3", t, s, KEYS.publicKey(),
                NOW.plus(Duration.ofMinutes(4)))).as("dans la fenêtre").isTrue();
        assertThat(SignedRead.verify("box", "/directory/v1/entries?since=3", t, s, KEYS.publicKey(),
                NOW.plus(Duration.ofMinutes(6)))).as("trop ancienne").isFalse();
        assertThat(SignedRead.verify("box", "/directory/v1/entries?since=3", t, s, KEYS.publicKey(),
                NOW.minus(Duration.ofMinutes(6)))).as("du futur").isFalse();
        assertThat(SignedRead.verify("box", "/directory/v1/entries?since=0", t, s, KEYS.publicKey(), NOW))
                .as("autre chemin").isFalse();
        assertThat(SignedRead.verify("autre", "/directory/v1/entries?since=3", t, s, KEYS.publicKey(), NOW))
                .as("autre lecteur").isFalse();
        assertThat(SignedRead.verify("box", "/directory/v1/entries?since=3", t, s, HybridKeyPair.generate()
                .publicKey(), NOW)).as("autre clé").isFalse();
        assertThat(SignedRead.verify(null, "/p", t, s, KEYS.publicKey(), NOW)).isFalse();
        assertThat(SignedRead.verify("box", "/p", null, s, KEYS.publicKey(), NOW)).isFalse();
        assertThat(SignedRead.verify("box", "/p", t, null, KEYS.publicKey(), NOW)).isFalse();
        assertThat(SignedRead.verify("box", "/p", "midi", s, KEYS.publicKey(), NOW)).isFalse();
        assertThat(SignedRead.verify("box", "/p", t, "une-seule-partie", KEYS.publicKey(), NOW)).isFalse();
        assertThat(SignedRead.verify("box", "/p", t, "%%%.%%%", KEYS.publicKey(), NOW)).isFalse();
    }

    private DirectoryClient client() {
        return new DirectoryClient(url, Duration.ofSeconds(5)).clock(() -> now);
    }

    @Test
    void entriesAreReadByDifferenceWithAFullReadEveryDay() {
        UUID box = UUID.randomUUID();
        DirectoryClient client = client().readAs(new SignedRead(box.toString(), KEYS));
        assertThat(client.fetch()).isEmpty();
        assertThat(client.fetch()).isEmpty();
        now = now.plus(Duration.ofHours(25));
        client.fetch();
        client.resetCursors();
        client.fetch();
        assertThat(seen).extracting(Seen::pathAndQuery).containsExactly("/directory/v1/entries?since=0",
                "/directory/v1/entries?since=7", "/directory/v1/entries?since=0", "/directory/v1/entries?since=0");
        assertThat(seen).allSatisfy(s -> {
            assertThat(s.reader()).isEqualTo(box.toString());
            assertThat(s.authorization()).isNull();
        });

        // Ancien annuaire, sans curseur : chaque lecture reste complète.
        listBody = "{\"organization\":\"grand-est\",\"entries\":[]}";
        seen.clear();
        client.resetCursors();
        client.fetch();
        client.fetch();
        assertThat(seen).extracting(Seen::pathAndQuery).containsOnly("/directory/v1/entries?since=0");
    }

    @Test
    void partnerEntriesHaveTheirOwnCursor() {
        String partner = B.publicKey().fingerprint();
        DirectoryClient client = client().account("lecteur:secret");
        client.fetchPartnerEntries(partner);
        client.fetchPartnerEntries(partner);
        listBody = "{\"organization\":\"bourgogne-fc\",\"entries\":[]}";
        client.fetchPartnerEntries(partner);
        client.fetchPartnerEntries(partner);
        listBody = "{\"organization\":\"bourgogne-fc\",\"entries\":[],\"cursor\":9}";
        client.fetchPartnerEntries(partner);
        now = now.plus(Duration.ofDays(2));
        client.fetchPartnerEntries(partner);
        String base = "/directory/v1/partners/" + partner + "/entries?since=";
        assertThat(seen).extracting(Seen::pathAndQuery).containsExactly(base + 0, base + 7, base + 7, base + 0,
                base + 0, base + 0);
        assertThat(seen).allSatisfy(s -> assertThat(s.authorization()).startsWith("Basic "));
        assertThat(client.account(" ").fetchConfigurations()).isEmpty();
        assertThat(seen.getLast().authorization()).as("compte vide : sans compte").isNull();
        client.account("lecteur:").fetchConfigurations();
        assertThat(seen.getLast().authorization()).as("mot de passe vide : sans compte").isNull();
    }

    @Test
    void aBoxNotYetEnrolledReadsItsOwnEntryWhenTheListIsReserved() {
        UUID box = UUID.randomUUID();
        SignedDocument entry = A.enroll(EnrollmentRequest.create(KEYS, box, "Box", Map.of(), NOW), NOW,
                Duration.ofDays(90), 1);
        DirectoryClient client = client().readAs(new SignedRead(box.toString(), KEYS));
        listStatus = 401;
        ownStatus = 404;
        assertThat(client.fetch()).isEmpty();
        ownStatus = 200;
        ownBody = entry.toJson();
        assertThat(client.fetch()).containsExactly(entry);
        listStatus = 403;
        ownStatus = 500;
        assertThatThrownBy(client::fetch).hasMessageContaining("500");
        assertThat(seen.getLast().pathAndQuery()).isEqualTo("/directory/v1/entries/" + box);
        assertThat(seen.getLast().reader()).as("entrée publique : pas de signature").isNull();

        // Un service d'annuaire partenaire, lui, n'a pas d'entrée à lire : le refus remonte.
        DirectoryClient partner = client().readAs(new SignedRead(SignedRead.ORGANIZATION + A.publicKey()
                .fingerprint(), KEYS));
        listStatus = 401;
        assertThatThrownBy(partner::fetch).hasMessageContaining("401");
        assertThatThrownBy(client()::fetch).as("sans lecteur : pas de repli").hasMessageContaining("401");
        listStatus = 500;
        assertThatThrownBy(client().readAs(new SignedRead(box.toString(), KEYS))::fetch).hasMessageContaining("500");
    }

    @Test
    void theViewIsIncompleteWhenAProofAnnouncesWhatTheDifferenceMissed() {
        SerialMemory serials = SerialMemory.inMemory();
        Clock clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        HybridKeyPair freshA = HybridKeyPair.generate();
        HybridKeyPair freshB = HybridKeyPair.generate();
        LocalDirectory directory = new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(), serials, clock),
                new LocalDirectoryTest.MemoryCache(), clock)
                .federate(new RecognitionVerifier(A.id(), A.publicKey(), serials), serials)
                .checkFreshness(A.id(), A.publicKey(), Duration.ofHours(48));
        assertThat(directory.incomplete()).as("sans preuve").isFalse();
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        directory.synchronize(() -> List.of(entry(A, self, 1)));
        directory.synchronizeFreshness(() -> Optional.of(new Freshness(A.delegateFreshness(freshA.publicKey(),
                NOW.minusSeconds(60), Duration.ofDays(30), 1), FreshnessProof.sign(freshA, A.id(), NOW,
                Map.of(self, 1L), Map.of()))));
        assertThat(directory.incomplete()).isFalse();
        directory.synchronizeFreshness(() -> Optional.of(new Freshness(A.delegateFreshness(freshA.publicKey(),
                NOW.minusSeconds(60), Duration.ofDays(30), 1), FreshnessProof.sign(freshA, A.id(), NOW.plusSeconds(1),
                Map.of(self, 1L, other, 1L), Map.of()))));
        assertThat(directory.incomplete()).as("box manquante").isTrue();
        directory.synchronize(() -> List.of(entry(A, other, 1)));
        assertThat(directory.incomplete()).isFalse();
        directory.synchronizeFreshness(() -> Optional.of(new Freshness(A.delegateFreshness(freshA.publicKey(),
                NOW.minusSeconds(60), Duration.ofDays(30), 1), FreshnessProof.sign(freshA, A.id(), NOW.plusSeconds(2),
                Map.of(self, 2L, other, 1L), Map.of()))));
        assertThat(directory.incomplete()).as("entrée en retard").isTrue();
        directory.synchronize(() -> List.of(entry(A, self, 2)));
        assertThat(directory.incomplete()).isFalse();

        // Partenaire reconnu : ses box manquantes ou en retard comptent ; une collision écartée, non.
        String fpB = B.publicKey().fingerprint();
        Recognition.Partner partner = new Recognition.Partner(B.id(), B.publicKey().encoded(),
                "https://annuaire.b.example", List.of("https://relais.b.example"));
        directory.synchronizeRecognitions(() -> List.of(A.recognize(partner, new Recognition.Scope(List.of("*"),
                List.of("*")), NOW, Duration.ofDays(90))));
        UUID nord = UUID.randomUUID();
        List<Freshness> proofsB = new ArrayList<>();
        java.util.function.Function<Map<UUID, Long>, Freshness> proofB = m -> {
            Freshness f = new Freshness(B.delegateFreshness(freshB.publicKey(), NOW.minusSeconds(60),
                    Duration.ofDays(30), 1), FreshnessProof.sign(freshB, B.id(), NOW.plusSeconds(proofsB.size()), m,
                    Map.of()));
            proofsB.add(f);
            return f;
        };
        directory.synchronizePartnerFreshness(fpB, () -> Optional.of(proofB.apply(Map.of(nord, 1L, self, 1L))));
        assertThat(directory.incomplete()).as("box du partenaire manquante").isTrue();
        directory.synchronizePartner(fpB, () -> List.of(entry(B, nord, 1)));
        assertThat(directory.incomplete()).as("collision avec notre box : pas un manque").isFalse();
        directory.synchronizePartnerFreshness(fpB, () -> Optional.of(proofB.apply(Map.of(nord, 2L))));
        assertThat(directory.incomplete()).as("box du partenaire en retard").isTrue();
        directory.synchronizePartner(fpB, () -> List.of(entry(B, nord, 2)));
        assertThat(directory.incomplete()).isFalse();
    }

    private static SignedDocument entry(Organization o, UUID box, long serial) {
        return o.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), HybridKemKeyPair.generate().publicKey(), box,
                "Box " + serial, Map.of("iu", "https://relais.example/iu/v1/queues/q"), NOW), NOW, Duration.ofDays(90),
                serial, List.of(), "Box " + serial);
    }
}
