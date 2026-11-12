package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.EntryCache;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.domain.transfer.TransferState;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.orchestration.PeerAuthorization;
import com.imagunity.orchestration.PeerHandshake;
import com.imagunity.transport.DisabledTransport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Annuaire activé dans l'application, face à un faux service d'annuaire. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectoryWiringTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private static final Organization ORGANIZATION = new Organization("chu-exemple", ORG_KEYS);
    private static final UUID PARTNER = UUID.randomUUID();
    private static HttpServer directoryService;
    private static Path dir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            dir = Files.createTempDirectory("box-annuaire");
            Path key = dir.resolve("organisation.pub.json");
            KeyFiles.writeOrganizationPublic(key, "chu-exemple", ORG_KEYS.publicKey());
            SignedDocument partner = ORGANIZATION.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), PARTNER,
                    "CHU Exemple — site B", Map.of(), Instant.now()), Instant.now(), Duration.ofDays(30), 1);
            directoryService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            directoryService.createContext("/directory/v1/entries", exchange -> {
                byte[] body = ("{\"organization\":\"chu-exemple\",\"entries\":[" + partner.toJson() + "]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            directoryService.start();
            registry.add("imagunity.directory.enabled", () -> "true");
            registry.add("imagunity.directory.url", () -> "http://127.0.0.1:" + directoryService.getAddress().getPort());
            registry.add("imagunity.directory.organization-key", key::toString);
            // Empreinte recopiée « à la main » : minuscules et espaces acceptés.
            registry.add("imagunity.directory.organization-code",
                    () -> ORG_KEYS.publicKey().shortCode().toLowerCase().replace("-", " "));
            registry.add("imagunity.directory.identity-file", () -> dir.resolve("identite.json").toString());
            registry.add("imagunity.orchestration.outbox-dir", () -> dir.resolve("outbox").toString());
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:annuaire-" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stop() {
        directoryService.stop(0);
    }

    @Autowired
    private Environment environment;
    @Autowired
    private LocalDirectory directory;
    @Autowired
    private BoxIdentity identity;
    @Autowired
    private PeerAuthorization authorization;
    @Autowired
    private com.imagunity.orchestration.SendOrchestrator sendOrchestrator;
    @Autowired
    private TransferRepository transfers;
    @Autowired
    private PeerRepository peers;

    private HttpResponse<String> get(String path) throws Exception {
        String credentials = Base64.getEncoder().encodeToString("operateur:mot-de-passe-de-test".getBytes());
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getProperty("local.server.port") + path))
                .header("Authorization", "Basic " + credentials).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void synchronizesAndShowsTheDirectory() throws Exception {
        for (int i = 0; i < 100 && directory.entry(PARTNER).isEmpty(); i++) {
            Thread.sleep(50);
        }

        HttpResponse<String> view = get("/api/v1/directory");
        HttpResponse<String> request = get("/api/v1/directory/enrollment-request?name=Site%20A");

        assertThat(directory.entry(PARTNER)).isPresent();
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(view.body()).contains("\"enabled\":true").contains(identity.boxId().toString())
                .contains("CHU Exemple — site B").contains("ACTIVE");
        assertThat(EnrollmentRequest.verify(SignedDocument.fromJson(request.body())).boxId())
                .isEqualTo(identity.boxId());
        assertThat(Files.readString(dir.resolve("identite.json.id")).strip()).isEqualTo(identity.boxId().toString());
    }

    @Test
    void unverifiedPartnerIsRefused() throws Exception {
        peers.save(new Peer("box-b", 5, "box-b", Instant.now()));
        Path file = Files.write(Files.createDirectories(dir.resolve("in/2.25.8/2.25.8.1")).resolve("i.dcm"),
                new byte[300]);

        sendOrchestrator.submit(new ReceivedStudy("2.25.8", "IU_TO_B", "box-b", Set.of("ORTHANC_A"),
                List.of(new ReceivedInstance("1.2", "2.25.8.1.1", "2.25.8.1", "1.2", file, 300)), Instant.now(),
                Instant.now()));
        Optional<Transfer> failed = Optional.empty();
        for (int i = 0; i < 400 && failed.isEmpty(); i++) {
            Thread.sleep(50);
            failed = transfers.findByStates(Set.of(TransferState.FAILED)).stream()
                    .filter(t -> t.studyInstanceUid().equals("2.25.8")).findFirst();
        }

        assertThat(authorization.refusal(peers.findByName("box-b").orElseThrow())).isPresent();
        assertThat(failed).hasValueSatisfying(t -> assertThat(t.errorMessage()).contains("pas encore vérifié"));
    }

    @Test
    void startupChecks() throws IOException {
        DirectoryConfiguration configuration = new DirectoryConfiguration();
        Path key = dir.resolve("organisation.pub.json");

        assertThatThrownBy(() -> configuration.organizationKey(new DirectoryProperties(true, null, key, "x", key,
                Duration.ofMinutes(5)))).hasMessageContaining("IMAGUNITY_DIRECTORY_URL");
        assertThatThrownBy(() -> configuration.organizationKey(new DirectoryProperties(true, URI.create("http://x"),
                null, "x", key, Duration.ofMinutes(5)))).hasMessageContaining("IMAGUNITY_DIRECTORY_ORGANIZATION_KEY");
        assertThatThrownBy(() -> configuration.organizationKey(new DirectoryProperties(true, URI.create("http://x"),
                key, "AAAA-BBBB", key, Duration.ofMinutes(5))))
                .hasMessageContaining(ORG_KEYS.publicKey().shortCode());
        assertThat(DirectoryConfiguration.normalize(null)).isEmpty();

        Path keys = dir.resolve("autre/identite.json");
        BoxIdentity created = DirectoryConfiguration.loadOrCreateIdentity(keys);
        BoxIdentity reloaded = DirectoryConfiguration.loadOrCreateIdentity(keys);
        assertThat(reloaded.boxId()).isEqualTo(created.boxId());
        assertThat(reloaded.keys().publicKey().fingerprint()).isEqualTo(created.keys().publicKey().fingerprint());

        // Identité en clair, puis phrase de passe fournie : chiffrée sur place, même identité.
        char[] phrase = "phrase de la box de test".toCharArray();
        BoxIdentity migrated = DirectoryConfiguration.loadOrCreateIdentity(keys, phrase);
        assertThat(migrated.boxId()).isEqualTo(created.boxId());
        assertThat(KeyFiles.isProtected(keys)).isTrue();
        assertThat(DirectoryConfiguration.loadOrCreateIdentity(keys, phrase).boxId()).isEqualTo(created.boxId());
        assertThatThrownBy(() -> DirectoryConfiguration.loadOrCreateIdentity(keys, null))
                .hasMessageContaining("IMAGUNITY_DIRECTORY_IDENTITY_PASSPHRASE_FILE");

        Path fresh = dir.resolve("neuve/identite.json");
        DirectoryConfiguration.loadOrCreateIdentity(fresh, phrase);
        assertThat(KeyFiles.isProtected(fresh)).isTrue();

        Path phraseFile = Files.writeString(dir.resolve("phrase.txt"), "phrase de la box de test\n");
        assertThat(new DirectoryProperties(true, null, null, null, keys, Duration.ofMinutes(5), "ignorée",
                phraseFile.toString(), true).passphrase()).isEqualTo(phrase);
        assertThat(new DirectoryProperties(true, null, null, null, keys, Duration.ofMinutes(5), "directe", "", true)
                .passphrase()).isEqualTo("directe".toCharArray());
        assertThat(new DirectoryProperties(true, null, null, null, keys, Duration.ofMinutes(5), "", null, true)
                .passphrase()).isNull();
        Path emptyFile = Files.writeString(dir.resolve("vide.txt"), "\n");
        assertThat(new DirectoryProperties(true, null, null, null, keys, Duration.ofMinutes(5), null,
                emptyFile.toString(), true).passphrase()).isNull();
    }

    @Test
    void encryptionKeysAreCreatedProtectedAndMigrated() throws IOException {
        char[] phrase = "phrase de la box de test".toCharArray();
        Path fresh = dir.resolve("chiffree/identite.json");
        BoxIdentity created = DirectoryConfiguration.loadOrCreateIdentity(fresh, phrase);
        Path encryption = DirectoryConfiguration.encryptionFileOf(fresh);
        assertThat(KeyFiles.isProtected(encryption)).isTrue();
        assertThat(DirectoryConfiguration.loadOrCreateIdentity(fresh, phrase).encryption().publicKey().fingerprint())
                .isEqualTo(created.encryption().publicKey().fingerprint());

        // Identité antérieure au chiffrement applicatif : ses clés de chiffrement sont créées au démarrage.
        Path older = dir.resolve("ancienne/identite.json");
        BoxIdentity before = DirectoryConfiguration.loadOrCreateIdentity(older);
        Files.delete(DirectoryConfiguration.encryptionFileOf(older));
        BoxIdentity after = DirectoryConfiguration.loadOrCreateIdentity(older);
        assertThat(after.boxId()).isEqualTo(before.boxId());
        assertThat(Files.exists(DirectoryConfiguration.encryptionFileOf(older))).isTrue();
        assertThat(KeyFiles.isProtected(DirectoryConfiguration.encryptionFileOf(older))).isFalse();

        // Phrase de passe fournie ensuite : les deux fichiers sont chiffrés sur place.
        BoxIdentity migrated = DirectoryConfiguration.loadOrCreateIdentity(older, phrase);
        assertThat(KeyFiles.isProtected(DirectoryConfiguration.encryptionFileOf(older))).isTrue();
        assertThat(migrated.encryption().publicKey().fingerprint())
                .isEqualTo(after.encryption().publicKey().fingerprint());

        DirectoryConfiguration configuration = new DirectoryConfiguration();
        assertThat(configuration.packetEncryption(null, created, new DirectoryProperties(true, null, null, null,
                fresh, Duration.ofMinutes(5), null, null, false))).isNotNull();
    }

    @Test
    void unreachableDirectoryStillGreetsPartners() {
        EntryCache none = new EntryCache() {
            @Override
            public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
            }

            @Override
            public List<SignedDocument> entries() {
                return List.of();
            }
        };
        LocalDirectory local = new LocalDirectory(new EntryVerifier("chu-exemple", ORG_KEYS.publicKey(),
                SerialMemory.inMemory(), Clock.systemUTC()), none, Clock.systemUTC());
        List<Runnable> greeted = new java.util.ArrayList<>();
        PeerHandshake handshake = new PeerHandshake(new InMemoryPeers(), new DisabledTransport(), identity, local,
                Runnable::run);

        DirectoryConfiguration.synchronize(local, new DirectoryClient(URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(1)), handshake, greeted::add);

        assertThat(local.lastSync().error()).contains("injoignable");
        assertThat(greeted).hasSize(1);
        greeted.getFirst().run();
    }

    /** Dépôt de partenaires vide. */
    private static final class InMemoryPeers implements PeerRepository {
        @Override
        public void save(Peer peer) {
        }

        @Override
        public Optional<Peer> findByName(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<Peer> findByTransportPeerId(long transportPeerId) {
            return Optional.empty();
        }

        @Override
        public List<Peer> findAll() {
            return List.of();
        }

        @Override
        public void delete(String name) {
        }
    }
}
