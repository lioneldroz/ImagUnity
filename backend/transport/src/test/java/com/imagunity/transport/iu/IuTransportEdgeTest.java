package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.EntryCache;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.SignedMessage;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cas limites de l'adaptateur IU-T : annuaire local, échecs de réception, fragments, état sur disque. */
class IuTransportEdgeTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Retry quick = new Retry(Duration.ofSeconds(1), Duration.ofMillis(100), d -> Thread.sleep(10));
    private final Map<UUID, IuDirectory.Partner> entries = new ConcurrentHashMap<>();
    private final IuDirectory directory = new IuDirectory() {
        @Override
        public Optional<IuDirectory.Partner> partner(UUID boxId) {
            return Optional.ofNullable(entries.get(boxId));
        }

        @Override
        public Optional<IuDirectory.Partner> byAddress(String address) {
            return entries.values().stream().filter(p -> address.equals(p.address())).findFirst();
        }
    };

    @BeforeAll
    static void startRelay() throws Exception {
        RelayHarness.start();
    }

    @Test
    void readsKeysAndAddressesFromTheLocalDirectory() {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Organization organization = new Organization("chu-exemple", HybridKeyPair.generate());
        EntryCache none = new EntryCache() {
            @Override
            public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
            }

            @Override
            public List<SignedDocument> entries() {
                return List.of();
            }
        };
        LocalDirectory local = new LocalDirectory(new EntryVerifier("chu-exemple", organization.publicKey(),
                SerialMemory.inMemory(), clock), none, clock);
        HybridKeyPair bKeys = HybridKeyPair.generate();
        HybridKemKeyPair bKem = HybridKemKeyPair.generate();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        String address = "https://relais.example/iu/v1/queues/" + "b".repeat(43);
        SignedDocument revokedEntry = organization.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), d,
                "Site D", Map.of("iu", "https://relais.example/iu/v1/queues/" + "d".repeat(43)), now), now,
                Duration.ofDays(30), 1);
        local.synchronize(() -> List.of(
                organization.enroll(EnrollmentRequest.create(bKeys, bKem.publicKey(), b, "Site B",
                        Map.of("iu", address), now), now, Duration.ofDays(30), 1),
                organization.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), c, "Site C",
                        Map.of("iu", "pas une adresse", "simplex", "simplex:/contact#c"), now), now,
                        Duration.ofDays(30), 1),
                organization.revoke(revokedEntry, now, "Box volée")));
        IuDirectory iu = IuDirectory.of(local, clock);

        IuDirectory.Partner partner = iu.partner(b).orElseThrow();
        assertThat(partner.active()).isTrue();
        assertThat(partner.displayName()).isEqualTo("Site B");
        assertThat(partner.address()).isEqualTo(address);
        assertThat(partner.encryptionKey()).isNotNull();
        assertThat(iu.byAddress(address)).map(IuDirectory.Partner::boxId).contains(b);
        assertThat(iu.partner(c).orElseThrow().address()).isNull();
        assertThat(iu.partner(c).orElseThrow().encryptionKey()).isNull();
        assertThat(iu.partner(d).orElseThrow().active()).isFalse();
        assertThat(iu.partner(UUID.randomUUID())).isEmpty();
        assertThat(iu.byAddress("https://relais.example/iu/v1/queues/" + "z".repeat(43))).isEmpty();
    }

    private IuTransport transport(String name, UUID id, HybridKeyPair keys, HybridKemKeyPair kem,
                                  List<TransportEvent> events) {
        IuTransport transport = new IuTransport(new IuTransport.Settings(RelayHarness.relayUrl, dir.resolve(name),
                Duration.ofHours(1), 1, Duration.ofDays(8)), id, keys, kem, directory, http, Clock.systemUTC(), quick);
        transport.addListener(events::add);
        entries.put(id, new IuDirectory.Partner(id, "Site " + name, true, keys.publicKey(), kem.publicKey(),
                transport.contactAddress()));
        return transport;
    }

    private static <T extends TransportEvent> T await(List<TransportEvent> events, Class<T> type)
            throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            for (TransportEvent event : events) {
                if (type.isInstance(event)) {
                    return type.cast(event);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Pas d'événement " + type.getSimpleName() + " : " + events);
    }

    @Test
    void aDownloadOfDiscardedChunksFailsAndBadFragmentsAreIgnored() throws Exception {
        List<TransportEvent> aEvents = new CopyOnWriteArrayList<>();
        List<TransportEvent> bEvents = new CopyOnWriteArrayList<>();
        try (IuTransport a = transport("a", RelayHarness.A, RelayHarness.A_KEYS, RelayHarness.A_KEM, aEvents);
             IuTransport b = transport("b", RelayHarness.B, RelayHarness.B_KEYS, RelayHarness.B_KEM, bEvents)) {
            a.start();
            b.start();
            a.connect(entries.get(RelayHarness.B).address());
            PeerId toB = await(aEvents, TransportEvent.PeerConnected.class).peer();
            await(bEvents, TransportEvent.PeerConnected.class);

            // Description trop longue : envoi en échec, morceaux retirés du relais.
            a.fragmentSize(1);
            byte[] large = new byte[30_000_000];
            new Random(2).nextBytes(large);
            long failedId = a.sendFile(toB, Files.write(dir.resolve("gros.tar.zst.iu1"), large));
            assertThat(await(aEvents, TransportEvent.FileFailed.class).fileId()).isEqualTo(failedId);

            // Morceaux retirés par l'émetteur avant le téléchargement : la réception échoue.
            a.fragmentSize(IuTransport.FRAGMENT);
            byte[] content = new byte[300_000];
            new Random(1).nextBytes(content);
            Path packet = Files.write(dir.resolve("paquet.tar.zst.iu1"), content);
            long fileId = a.sendFile(toB, packet);
            await(aEvents, TransportEvent.FileSent.class);
            TransportEvent.FileOffered offered = await(bEvents, TransportEvent.FileOffered.class);
            a.discardSentFile(fileId);
            for (int i = 0; i < 200 && a.store().outgoing(fileId).isPresent(); i++) {
                Thread.sleep(20);
            }
            b.receiveFile(offered.fileId(), dir.resolve("recu/autre-nom"));
            assertThat(await(bEvents, TransportEvent.FileFailed.class).fileId()).isEqualTo(offered.fileId());
            assertThat(b.store().incoming(offered.fileId())).map(IuStore.Incoming::status)
                    .contains(IuStore.Status.FAILED);

            // Fragments invalides, et fragment rejoué d'une description déjà complète : sans effet.
            IuStore.Incoming known = b.store().incoming(offered.fileId()).orElseThrow();
            IuStore.Contact contact = b.store().contactOf(RelayHarness.A).orElseThrow();
            int before = b.store().incoming().size();
            for (IuTransport.Fragment bad : List.of(new IuTransport.Fragment(null, 0, 1, "x"),
                    new IuTransport.Fragment("t", 0, 1, null), new IuTransport.Fragment("t", 0, 0, "x"),
                    new IuTransport.Fragment("t", 0, IuTransport.MAX_FRAGMENTS + 1, "x"),
                    new IuTransport.Fragment("t", -1, 2, "x"), new IuTransport.Fragment(known.transferId(), 0, 1,
                            "rejoué"))) {
                b.onMessage(SignedMessage.of(IuTransport.FILE, RelayHarness.A, RelayHarness.B, Codec.json().writeValueAsString(bad),
                        Instant.now()));
            }
            assertThat(b.store().incoming()).hasSize(before);
            assertThat(contact.boxId()).isEqualTo(RelayHarness.A);
            assertThat(b.authenticatedBox(new com.imagunity.transport.PeerId(contact.peerId())))
                    .as("box authentifiée du pair").contains(RelayHarness.A);
            assertThat(b.authenticatedBox(new com.imagunity.transport.PeerId(424242))).isEmpty();

            // Description complète mais d'un autre transfert que ses fragments : écartée.
            b.onMessage(SignedMessage.of(IuTransport.FILE, RelayHarness.A, RelayHarness.B, Codec.json()
                    .writeValueAsString(new IuTransport.Fragment("AUTRETRANSFERTxxxxxxxx", 0, 1,
                            known.description())), Instant.now()));
            assertThat(b.store().incoming()).hasSize(before);

            // Descriptions incomplètes d'un même pair : en nombre limité.
            for (int i = 0; i <= IuTransport.MAX_COLLECTING; i++) {
                b.onMessage(SignedMessage.of(IuTransport.FILE, RelayHarness.A, RelayHarness.B, Codec.json()
                        .writeValueAsString(new IuTransport.Fragment(String.format("F%021d", i), 0, 2, "x")),
                        Instant.now()));
            }
            assertThat(b.store().incoming()).hasSize(before + IuTransport.MAX_COLLECTING);
            assertThat(b.busy()).isFalse();
        }
    }

    @Test
    void storeFailuresSurfaceAsUncheckedErrors() throws Exception {
        IuStore store = new IuStore(dir.resolve("etat"));
        assertThat(store.contact(42)).isEmpty();
        // Dossier des réceptions remplacé par un fichier : lecture impossible.
        Files.delete(dir.resolve("etat/receptions"));
        Files.writeString(dir.resolve("etat/receptions"), "x");
        assertThatThrownBy(store::incoming).isInstanceOf(UncheckedIOException.class);
        // Écriture impossible : dossier absent.
        assertThatThrownBy(() -> store.save(new IuStore.Incoming(1, 1, RelayHarness.A, "t", null, null, null,
                List.of(), IuStore.Status.OFFERED, Instant.now()))).isInstanceOf(UncheckedIOException.class);
        // Fichier d'état illisible (un dossier à sa place) ; suppression impossible (dossier non vide).
        Files.createDirectories(dir.resolve("etat/envois/7.json/x"));
        assertThatThrownBy(() -> store.outgoing(7)).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.deleteOutgoing(7)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void closedTransportStartsNothing() {
        IuTransport transport = new IuTransport(new IuTransport.Settings(URI.create("http://127.0.0.1:1"),
                dir.resolve("ferme"), Duration.ofHours(1), 1, Duration.ofDays(8)), RelayHarness.A,
                RelayHarness.A_KEYS, RelayHarness.A_KEM, directory, http, Clock.systemUTC(), quick);
        transport.close();
        transport.start();
        assertThat(transport.busy()).isFalse();
    }
}
