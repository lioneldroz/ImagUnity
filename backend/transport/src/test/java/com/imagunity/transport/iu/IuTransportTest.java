package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.FileDescription;
import com.imagunity.protocol.MessageEnvelope;
import com.imagunity.protocol.SignedMessage;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Adaptateur IU-T du {@code TransportPort}, de bout en bout contre un vrai relais : deux box A et B. */
class IuTransportTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Retry quick = new Retry(Duration.ofSeconds(2), Duration.ofMillis(200), d -> Thread.sleep(20));
    /** Annuaire des essais : entrées modifiables (adresse, statut). */
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
    private final List<IuTransport> opened = new ArrayList<>();

    @BeforeAll
    static void startRelay() throws Exception {
        RelayHarness.start();
    }

    @AfterEach
    void closeAll() {
        opened.forEach(IuTransport::close);
    }

    /** Box de l'essai : son transport et les événements reçus. */
    private record Box(IuTransport transport, List<TransportEvent> events) {

        <T extends TransportEvent> T await(Class<T> type, Predicate<T> condition) throws InterruptedException {
            for (int i = 0; i < 600; i++) {
                for (TransportEvent event : events) {
                    if (type.isInstance(event) && condition.test(type.cast(event))) {
                        return type.cast(event);
                    }
                }
                Thread.sleep(20);
            }
            throw new AssertionError("Pas d'événement " + type.getSimpleName() + " : " + events);
        }

        <T extends TransportEvent> T await(Class<T> type) throws InterruptedException {
            return await(type, e -> true);
        }

        <T extends TransportEvent> List<T> all(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    private Box box(String name, UUID id, HybridKeyPair keys, HybridKemKeyPair kem, boolean start) {
        IuTransport transport = new IuTransport(new IuTransport.Settings(URI.create(RelayHarness.relayUrl + "/"),
                dir.resolve(name), Duration.ofHours(1), 2, Duration.ofDays(8)), id, keys, kem, directory, http,
                Clock.systemUTC(), quick);
        opened.add(transport);
        List<TransportEvent> events = new CopyOnWriteArrayList<>();
        transport.addListener(events::add);
        String address = transport.contactAddress();
        entries.put(id, new IuDirectory.Partner(id, "Site " + name.toUpperCase(), true, keys.publicKey(),
                kem.publicKey(), address));
        if (start) {
            transport.start();
        }
        return new Box(transport, events);
    }

    private Box a(boolean start) {
        return box("a", RelayHarness.A, RelayHarness.A_KEYS, RelayHarness.A_KEM, start);
    }

    private Box b(boolean start) {
        return box("b", RelayHarness.B, RelayHarness.B_KEYS, RelayHarness.B_KEM, start);
    }

    /** A appaire B par son adresse d'annuaire ; B crée le contact à la réception. */
    private PeerId[] pair(Box a, Box b) throws InterruptedException {
        a.transport().acceptInvitation(entries.get(RelayHarness.B).address());
        PeerId bAtA = a.await(TransportEvent.PeerConnected.class).peer();
        PeerId aAtB = b.await(TransportEvent.PeerConnected.class).peer();
        return new PeerId[] {bAtA, aAtB};
    }

    private static Path randomFile(Path file, int size) throws Exception {
        byte[] content = new byte[size];
        new Random(size).nextBytes(content);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        return file;
    }

    @Test
    void pairsThroughTheDirectoryThenExchangesMessagesAndFiles() throws Exception {
        Box a = a(true);
        Box b = b(true);
        assertThat(a.transport().addressKind()).isEqualTo("iu");
        assertThat(a.transport().contactAddress()).as("file créée une fois").isEqualTo(entries.get(RelayHarness.A)
                .address());

        PeerId[] peers = pair(a, b);
        assertThat(b.await(TransportEvent.PeerConnected.class).displayName()).isEqualTo("Site A");
        assertThat(a.await(TransportEvent.PeerConnected.class).displayName()).isEqualTo("Site B");
        a.transport().acceptInvitation(entries.get(RelayHarness.B).address());
        assertThat(a.all(TransportEvent.PeerConnected.class)).as("même contact").extracting(e -> e.peer())
                .containsOnly(peers[0]);

        a.transport().sendMessage(peers[0], "{\"type\":\"annonce\"}");
        b.transport().sendMessage(peers[1], "accusé");
        assertThat(b.await(TransportEvent.MessageReceived.class).text()).isEqualTo("{\"type\":\"annonce\"}");
        assertThat(a.await(TransportEvent.MessageReceived.class).text()).isEqualTo("accusé");
        assertThat(b.await(TransportEvent.MessageReceived.class).peer()).isEqualTo(peers[1]);

        // Fichier de 9 Mo : 3 morceaux, description en plusieurs fragments.
        a.transport().fragmentSize(300);
        Path packet = randomFile(dir.resolve("envoi/paquet-1.tar.zst.iu1"), 9_000_000);
        long fileId = a.transport().sendFile(peers[0], packet);
        assertThat(a.await(TransportEvent.FileSent.class).fileId()).isEqualTo(fileId);
        assertThat(a.all(TransportEvent.FileProgress.class)).isNotEmpty()
                .allSatisfy(p -> assertThat(p.direction()).isEqualTo(TransportEvent.Direction.SENDING))
                .last().satisfies(p -> assertThat(p.ratio()).isEqualTo(1.0));
        TransportEvent.FileOffered offered = b.await(TransportEvent.FileOffered.class);
        assertThat(offered.fileName()).isEqualTo("paquet-1.tar.zst.iu1");
        assertThat(offered.size()).isEqualTo(9_000_000);
        assertThat(offered.peer()).isEqualTo(peers[1]);

        Path target = dir.resolve("recu/" + offered.fileId() + "-paquet-1.tar.zst.iu1");
        b.transport().receiveFile(offered.fileId(), target);
        TransportEvent.FileReceived received = b.await(TransportEvent.FileReceived.class);
        assertThat(received.fileId()).isEqualTo(offered.fileId());
        assertThat(Files.mismatch(packet, received.path())).isEqualTo(-1);
        assertThat(b.all(TransportEvent.FileProgress.class)).isNotEmpty()
                .allSatisfy(p -> assertThat(p.direction()).isEqualTo(TransportEvent.Direction.RECEIVING));
        b.transport().receiveFile(offered.fileId(), target);
        assertThat(b.all(TransportEvent.FileReceived.class)).as("déjà reçu : redit").hasSize(2);

        // Rattrapage : ce qui est reçu ou envoyé est rejoué.
        b.transport().catchUp();
        a.transport().catchUp();
        assertThat(b.all(TransportEvent.FileReceived.class)).hasSize(3);
        assertThat(a.all(TransportEvent.FileSent.class)).hasSize(2);

        a.transport().discardSentFile(fileId);
        a.transport().discardSentFile(424242);
        for (int i = 0; i < 200 && a.transport().store().outgoing(fileId).isPresent(); i++) {
            Thread.sleep(20);
        }
        assertThat(a.transport().store().outgoing(fileId)).isEmpty();
    }

    @Test
    void transfersResumeAfterARestartOfEitherBox() throws Exception {
        Box a = a(true);
        Box b = b(true);
        PeerId[] peers = pair(a, b);

        // A s'arrête juste après avoir confié le fichier : l'envoi reprend à son redémarrage.
        Path packet = randomFile(dir.resolve("envoi/paquet-2.tar.zst.iu1"), 6_000_000);
        long fileId = a.transport().sendFile(peers[0], packet);
        a.transport().close();
        Box a2 = a(true);
        assertThat(a2.await(TransportEvent.FileSent.class).fileId()).isEqualTo(fileId);

        TransportEvent.FileOffered offered = b.await(TransportEvent.FileOffered.class);
        // B s'arrête avant d'accepter : le fichier est proposé de nouveau au rattrapage.
        b.transport().close();
        Box b2 = b(false);
        b2.transport().catchUp();
        assertThat(b2.await(TransportEvent.FileOffered.class).fileId()).isEqualTo(offered.fileId());
        b2.transport().start();
        Path target = dir.resolve("recu/paquet-2.tar.zst.iu1");
        b2.transport().receiveFile(offered.fileId(), target);
        // Arrêt en cours de téléchargement : la réception reprend au démarrage suivant.
        b2.transport().close();
        Box b3 = b(true);
        TransportEvent.FileReceived received = b3.await(TransportEvent.FileReceived.class);
        assertThat(Files.mismatch(packet, received.path())).isEqualTo(-1);
        assertThat(b3.transport().busy()).isFalse();
    }

    @Test
    void messagesWaitInTheOutboxWhileTheRelayRefusesThem() throws Exception {
        Box a = a(true);
        Box b = b(true);
        PeerId[] peers = pair(a, b);
        IuDirectory.Partner real = entries.get(RelayHarness.B);
        // Adresse de B inconnue du relais (file renouvelée, annuaire pas encore à jour) : le message attend.
        entries.put(RelayHarness.B, new IuDirectory.Partner(real.boxId(), real.displayName(), true,
                real.signingKey(), real.encryptionKey(), RelayHarness.relayUrl + "/iu/v1/queues/" + "x".repeat(43)));
        a.transport().sendMessage(peers[0], "patience");
        assertThat(a.transport().outbox().flush()).isFalse();
        assertThat(a.transport().store().pending()).hasSize(1);
        entries.put(RelayHarness.B, real);
        assertThat(b.await(TransportEvent.MessageReceived.class, m -> m.text().equals("patience"))).isNotNull();
        assertThat(a.transport().store().pending()).isEmpty();

        // Box sans adresse, puis relais injoignable : gardé ; trop vieux : abandonné.
        entries.put(RelayHarness.B, new IuDirectory.Partner(real.boxId(), real.displayName(), false,
                real.signingKey(), real.encryptionKey(), real.address()));
        IuStore store = new IuStore(dir.resolve("boite"));
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        IuOutbox outbox = new IuOutbox(store, new IuMsgClient(http, Clock.systemUTC()), RelayHarness.A,
                RelayHarness.A_KEYS, id -> Optional.empty(), Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(1));
        store.addPending(RelayHarness.B, new byte[] {1}, now);
        assertThat(outbox.flush()).isFalse();
        IuOutbox down = new IuOutbox(store, new IuMsgClient(http, Clock.systemUTC()), RelayHarness.A,
                RelayHarness.A_KEYS, id -> Optional.of(QueueAddress.parse("http://127.0.0.1:1/iu/v1/queues/"
                + "y".repeat(43))), Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(1));
        assertThat(down.flush()).isFalse();
        IuOutbox later = new IuOutbox(store, new IuMsgClient(http, Clock.systemUTC()), RelayHarness.A,
                RelayHarness.A_KEYS, id -> Optional.empty(), Clock.fixed(now.plus(Duration.ofDays(2)),
                ZoneOffset.UTC), Duration.ofDays(1));
        assertThat(later.flush()).isTrue();
        assertThat(store.pending()).isEmpty();
        // Refus définitif (message vide) : abandonné.
        store.addPending(RelayHarness.B, new byte[0], now);
        IuOutbox refused = new IuOutbox(store, new IuMsgClient(http, Clock.systemUTC()), RelayHarness.A,
                RelayHarness.A_KEYS, id -> Optional.of(QueueAddress.parse(real.address())),
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(1));
        assertThat(refused.flush()).isTrue();
        assertThat(store.pending()).isEmpty();
        refused.close();
    }

    @Test
    void refusesWhatTheDirectoryDoesNotAllow() throws Exception {
        Box a = a(true);
        Box b = b(true);
        assertThatThrownBy(() -> a.transport().createInvitation()).isInstanceOf(TransportException.class);
        assertThatThrownBy(() -> a.transport().acceptContactRequest(1)).isInstanceOf(TransportException.class);
        assertThatThrownBy(() -> a.transport().acceptInvitation("simplex:/contact#x"))
                .hasMessageContaining("invalide");
        assertThatThrownBy(() -> a.transport().acceptInvitation(RelayHarness.relayUrl + "/iu/v1/queues/"
                + "z".repeat(43))).hasMessageContaining("Aucune box");
        assertThatThrownBy(() -> a.transport().acceptInvitation(entries.get(RelayHarness.A).address()))
                .hasMessageContaining("cette box");
        assertThatThrownBy(() -> a.transport().sendMessage(new PeerId(999), "x")).hasMessageContaining("inconnu");
        assertThatThrownBy(() -> a.transport().receiveFile(999, dir.resolve("x"))).hasMessageContaining("inconnu");
        assertThatThrownBy(() -> a.transport().sendFile(new PeerId(1), dir.resolve("absent")))
                .hasMessageContaining("introuvable");

        PeerId[] peers = pair(a, b);
        IuDirectory.Partner real = entries.get(RelayHarness.B);
        entries.put(RelayHarness.B, new IuDirectory.Partner(real.boxId(), real.displayName(), false,
                real.signingKey(), real.encryptionKey(), real.address()));
        assertThatThrownBy(() -> a.transport().sendMessage(peers[0], "x")).hasMessageContaining("non active");
        entries.put(RelayHarness.B, new IuDirectory.Partner(real.boxId(), real.displayName(), true,
                real.signingKey(), null, real.address()));
        assertThatThrownBy(() -> a.transport().sendMessage(peers[0], "x")).hasMessageContaining("sans clés");
        entries.remove(RelayHarness.B);
        assertThatThrownBy(() -> a.transport().sendMessage(peers[0], "x")).hasMessageContaining("absente");
        entries.put(RelayHarness.B, real);
        assertThatThrownBy(() -> a.transport().sendMessage(peers[0], "x".repeat(70_000)))
                .hasMessageContaining("trop long");

        // Messages reçus que B écarte : émetteur inactif, type inconnu, fragments invalides, autre relais.
        IuMsgClient client = new IuMsgClient(http, Clock.systemUTC());
        QueueAddress toB = QueueAddress.parse(real.address());
        IuDirectory.Partner aEntry = entries.get(RelayHarness.A);
        entries.put(RelayHarness.A, new IuDirectory.Partner(aEntry.boxId(), aEntry.displayName(), false,
                aEntry.signingKey(), aEntry.encryptionKey(), aEntry.address()));
        b.transport().onMessage(SignedMessage.of(IuTransport.TEXT, RelayHarness.A, "d'une box révoquée",
                Instant.now()));
        entries.remove(RelayHarness.A);
        b.transport().onMessage(SignedMessage.of(IuTransport.TEXT, RelayHarness.A, "d'une box inconnue",
                Instant.now()));
        entries.put(RelayHarness.A, aEntry);
        deposit(client, toB, "inconnu", "x");
        deposit(client, toB, IuTransport.FILE, "pas du JSON");
        deposit(client, toB, IuTransport.FILE, Codec.json().writeValueAsString(
                new IuTransport.Fragment("t1", 3, 2, "x")));
        deposit(client, toB, IuTransport.FILE, Codec.json().writeValueAsString(
                new IuTransport.Fragment("t2", 0, 1, "{\"pas\":\"une description\"}")));
        deposit(client, toB, IuTransport.FILE, Codec.json().writeValueAsString(
                new IuTransport.Fragment("t3", 0, 2, "{")));
        deposit(client, toB, IuTransport.FILE, Codec.json().writeValueAsString(
                new IuTransport.Fragment("t3", 1, 3, "}")));
        FileDescription elsewhere = new FileDescription("IU1", "AAAAAAAAAAAAAAAAAAAAAA", FileDescription.COMPLETE,
                "paquet.tar.zst.iu1", 256 * 1024, 1, 10, "0".repeat(64), List.of(new FileDescription.Chunk(0,
                "0".repeat(64), List.of(new FileDescription.Replica("https://ailleurs.example", "r".repeat(43),
                Codec.base64Url(java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPrivate().getEncoded()))))), Instant.now().plus(Duration.ofHours(1)));
        deposit(client, toB, IuTransport.FILE, Codec.json().writeValueAsString(
                new IuTransport.Fragment("t4", 0, 1, elsewhere.toJson())));
        deposit(client, toB, IuTransport.TEXT, "fin");
        assertThat(b.await(TransportEvent.MessageReceived.class, m -> m.text().equals("fin"))).isNotNull();
        assertThat(b.all(TransportEvent.MessageReceived.class)).extracting(TransportEvent.MessageReceived::text)
                .doesNotContain("d'une box révoquée", "d'une box inconnue");
        assertThat(b.all(TransportEvent.FileOffered.class)).isEmpty();
        assertThat(b.transport().store().incoming()).as("seule t3 attend sa suite")
                .extracting(IuStore.Incoming::transferId).containsExactly("t3");
    }

    private static void deposit(IuMsgClient client, QueueAddress to, String type, String body) throws Exception {
        client.deposit(to, RelayHarness.A, RelayHarness.A_KEYS, SignedMessage.of(type, RelayHarness.A, body,
                Instant.now()).seal(RelayHarness.A_KEYS, RelayHarness.B_KEM.publicKey()));
    }

    @Test
    void aFullSizeFragmentStillFitsInOneMessage() throws Exception {
        // Description réelle d'un gros paquet : morceaux aux clés aléatoires (le pire cas pour la taille).
        List<IuFileUploader.ChunkState> chunks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String key = Codec.base64Url(java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                    .getPrivate().getEncoded());
            chunks.add(new IuFileUploader.ChunkState(i, "%064x".formatted(i), "s".repeat(43), key,
                    "r".repeat(43), key, true));
        }
        String json = IuFileUploader.description(new IuFileUploader.UploadState("AAAAAAAAAAAAAAAAAAAAAA",
                "paquet.tar.zst.iu1", RelayHarness.relayUrl.toString(), 4 * 1024 * 1024, 300L * 4 * 1024 * 1024,
                "0".repeat(64), Instant.now(), chunks)).toJson();
        assertThat(json.length()).isGreaterThan(2 * IuTransport.FRAGMENT);
        String body = Codec.json().writeValueAsString(new IuTransport.Fragment("AAAAAAAAAAAAAAAAAAAAAA", 0, 9,
                json.substring(0, IuTransport.FRAGMENT)));
        byte[] sealed = SignedMessage.of(IuTransport.FILE, RelayHarness.A, body, Instant.now())
                .seal(RelayHarness.A_KEYS, RelayHarness.B_KEM.publicKey());
        assertThat(MessageEnvelope.open(sealed, RelayHarness.B_KEM).length).isLessThan(MessageEnvelope.MAX_CONTENT);
    }

    @Test
    void forgetsFinishedTransfersAfterAWhileAndRejectsBadSettings() throws Exception {
        IuStore store = new IuStore(dir.resolve("vieux"));
        Instant old = Instant.now().minus(Duration.ofDays(30));
        store.save(new IuStore.Incoming(1, 1, RelayHarness.A, "t", null, null, null, List.of(),
                IuStore.Status.RECEIVED, old));
        store.save(new IuStore.Incoming(2, 1, RelayHarness.A, "u", null, null, null, List.of(),
                IuStore.Status.COLLECTING, Instant.now()));
        store.save(new IuStore.Outgoing(3, 1, RelayHarness.A, "x", null, true, old));
        IuTransport transport = new IuTransport(new IuTransport.Settings(RelayHarness.relayUrl, dir.resolve("vieux"),
                Duration.ofHours(1), 1, Duration.ofDays(8)), RelayHarness.A, RelayHarness.A_KEYS, RelayHarness.A_KEM,
                directory, http, Clock.systemUTC(), quick);
        opened.add(transport);
        transport.start();
        transport.start();
        assertThat(store.incoming()).extracting(IuStore.Incoming::fileId).containsExactly(2L);
        assertThat(store.outgoing()).isEmpty();
        assertThatThrownBy(() -> new IuTransport.Settings(RelayHarness.relayUrl, dir, Duration.ofHours(1), 0,
                Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IuStore(Files.writeString(dir.resolve("fichier"), "x")))
                .isInstanceOf(TransportException.class);
    }

    @Test
    void anUnreachableRelayIsRetriedAndAFailedSendIsReported() throws Exception {
        Box b = b(true);
        // Relais injoignable : la file n'est pas créée, la relève est réessayée en arrière-plan sans bloquer.
        IuTransport offline = new IuTransport(new IuTransport.Settings(URI.create("http://127.0.0.1:1"),
                dir.resolve("hors-ligne"), Duration.ofHours(1), 1, Duration.ofDays(8)), RelayHarness.A,
                RelayHarness.A_KEYS, RelayHarness.A_KEM, directory, http, Clock.systemUTC(),
                new Retry(Duration.ZERO, Duration.ofMillis(10), d -> Thread.sleep(5)));
        opened.add(offline);
        List<TransportEvent> events = new CopyOnWriteArrayList<>();
        offline.addListener(events::add);
        offline.addListener(e -> {
            throw new IllegalStateException("écouteur en panne");
        });
        String reserved = offline.contactAddress();
        assertThat(reserved).as("adresse réservée, stable").startsWith("http://127.0.0.1:1/iu/v1/queues/")
                .isEqualTo(offline.contactAddress());
        offline.start();
        Thread.sleep(100);

        // L'envoi d'un fichier échoue (relais injoignable) : FileFailed, état oublié.
        entries.put(RelayHarness.A, new IuDirectory.Partner(RelayHarness.A, "Site A", true,
                RelayHarness.A_KEYS.publicKey(), RelayHarness.A_KEM.publicKey(), RelayHarness.relayUrl
                + "/iu/v1/queues/" + "a".repeat(43)));
        offline.store().addContact(RelayHarness.B, new boolean[1]);
        long peer = offline.store().contactOf(RelayHarness.B).orElseThrow().peerId();
        Path packet = randomFile(dir.resolve("envoi/paquet-3.tar.zst.iu1"), 1000);
        long fileId = offline.sendFile(new PeerId(peer), packet);
        Box failed = new Box(offline, events);
        assertThat(failed.await(TransportEvent.FileFailed.class).fileId()).isEqualTo(fileId);
        assertThat(offline.store().outgoing(fileId)).isEmpty();
        offline.close();
        b.transport().close();
    }
}
