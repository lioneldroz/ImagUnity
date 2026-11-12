package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.FileDescription;
import com.imagunity.protocol.SignedMessage;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Annonce anticipée (spécification § 6.6) : le destinataire télécharge au fil du téléversement. */
class IuTransportEarlyTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Retry quick = new Retry(Duration.ofSeconds(2), Duration.ofMillis(200), d -> Thread.sleep(20));
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

    private IuTransport box(String name, UUID id, HybridKeyPair keys, HybridKemKeyPair kem, boolean early,
                            List<TransportEvent> events) {
        IuTransport transport = new IuTransport(new IuTransport.Settings(RelayHarness.relayUrl, dir.resolve(name),
                Duration.ofMinutes(10), 2, Duration.ofDays(8), early), id, keys, kem, directory, http,
                Clock.systemUTC(), quick);
        opened.add(transport);
        transport.addListener(events::add);
        entries.put(id, new IuDirectory.Partner(id, "Site " + name, true, keys.publicKey(), kem.publicKey(),
                transport.contactAddress()));
        transport.start();
        return transport;
    }

    private static <T extends TransportEvent> T await(List<TransportEvent> events, Class<T> type)
            throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            for (TransportEvent event : events) {
                if (type.isInstance(event)) {
                    return type.cast(event);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Pas d'événement " + type.getSimpleName() + " : " + events);
    }

    private Path packet(String name, int size) throws Exception {
        byte[] content = new byte[size];
        new Random(size).nextBytes(content);
        return Files.write(dir.resolve(name), content);
    }

    @Test
    void theReceiverStartsDownloadingBeforeTheChunksAreUploaded() throws Exception {
        List<TransportEvent> bEvents = new CopyOnWriteArrayList<>();
        IuTransport b = box("b", RelayHarness.B, RelayHarness.B_KEYS, RelayHarness.B_KEM, true, bEvents);

        // A crée les morceaux sans les téléverser, et annonce aussitôt le fichier (mode early).
        Path packet = packet("paquet-tot.tar.zst.iu1", 700_000);
        IuFileUploader uploader = new IuFileUploader(new IuFileClient(RelayHarness.relayUrl, http, Clock.systemUTC()),
                RelayHarness.A, RelayHarness.A_KEYS, Clock.systemUTC(), quick);
        IuFileUploader.UploadState state = uploader.prepare(packet, 256 * ChunkLayout.KIB, Duration.ofMinutes(10));
        FileDescription early = IuFileUploader.description(state, true);
        assertThat(early.early()).isTrue();
        entries.put(RelayHarness.A, new IuDirectory.Partner(RelayHarness.A, "Site a", true,
                RelayHarness.A_KEYS.publicKey(), RelayHarness.A_KEM.publicKey(), RelayHarness.relayUrl
                + "/iu/v1/queues/" + "a".repeat(43)));
        new IuMsgClient(http, Clock.systemUTC()).deposit(QueueAddress.parse(entries.get(RelayHarness.B).address()),
                RelayHarness.A, RelayHarness.A_KEYS, SignedMessage.of(IuTransport.FILE, RelayHarness.A,
                        Codec.json().writeValueAsString(new IuTransport.Fragment(state.transferId(), 0, 1,
                                early.toJson())), Instant.now())
                        .seal(RelayHarness.A_KEYS, RelayHarness.B_KEM.publicKey()));

        TransportEvent.FileOffered offered = await(bEvents, TransportEvent.FileOffered.class);
        Path target = dir.resolve("recu/paquet-tot.tar.zst.iu1");
        b.receiveFile(offered.fileId(), target);
        Thread.sleep(300);
        assertThat(bEvents).as("rien à télécharger encore").noneMatch(TransportEvent.FileReceived.class::isInstance);
        assertThat(b.busy()).as("téléchargement en attente des morceaux").isTrue();

        // Le téléversement commence après : B reçoit les morceaux au fil de l'eau.
        uploader.upload(packet, state, s -> { }, n -> { });
        TransportEvent.FileReceived received = await(bEvents, TransportEvent.FileReceived.class);
        assertThat(Files.mismatch(packet, received.path())).isEqualTo(-1);
        byte[] last = Files.readAllBytes(received.path());
        assertThat(last).hasSize(700_000);
    }

    @Test
    void bothModesDeliverAndTheDescriptionSaysWhichOneWasUsed() throws Exception {
        for (boolean early : new boolean[] {true, false}) {
            List<TransportEvent> aEvents = new CopyOnWriteArrayList<>();
            List<TransportEvent> bEvents = new CopyOnWriteArrayList<>();
            String suffix = early ? "tot" : "complet";
            IuTransport a = box("a-" + suffix, RelayHarness.A, RelayHarness.A_KEYS, RelayHarness.A_KEM, early,
                    aEvents);
            IuTransport b = box("b-" + suffix, RelayHarness.B, RelayHarness.B_KEYS, RelayHarness.B_KEM, early,
                    bEvents);
            a.connect(entries.get(RelayHarness.B).address());
            PeerId toB = await(aEvents, TransportEvent.PeerConnected.class).peer();
            await(bEvents, TransportEvent.PeerConnected.class);

            Path packet = packet("paquet-" + suffix + ".tar.zst.iu1", 5_000_000);
            long fileId = a.sendFile(toB, packet);
            TransportEvent.FileOffered offered = await(bEvents, TransportEvent.FileOffered.class);
            assertThat(FileDescription.fromJson(b.store().incoming(offered.fileId()).orElseThrow().description())
                    .early()).isEqualTo(early);
            b.receiveFile(offered.fileId(), dir.resolve("recu-" + suffix + "/" + offered.fileName()));
            assertThat(Files.mismatch(packet, await(bEvents, TransportEvent.FileReceived.class).path())).isEqualTo(-1);
            assertThat(await(aEvents, TransportEvent.FileSent.class).fileId()).isEqualTo(fileId);
            assertThat(a.store().outgoing(fileId).orElseThrow().described()).isTrue();
            assertThat(a.store().outgoing(fileId).orElseThrow().state().paddingKey()).as("remplissage aléatoire")
                    .isNotNull();
            a.close();
            b.close();
        }
    }

    @Test
    void anAbandonedEarlySendStopsTheReceiver() throws Exception {
        List<TransportEvent> bEvents = new CopyOnWriteArrayList<>();
        IuTransport b = box("b-abandon", RelayHarness.B, RelayHarness.B_KEYS, RelayHarness.B_KEM, true, bEvents);
        Path packet = packet("paquet-abandon.tar.zst.iu1", 300_000);
        IuFileUploader uploader = new IuFileUploader(new IuFileClient(RelayHarness.relayUrl, http, Clock.systemUTC()),
                RelayHarness.A, RelayHarness.A_KEYS, Clock.systemUTC(), quick);
        IuFileUploader.UploadState state = uploader.prepare(packet, 256 * ChunkLayout.KIB, Duration.ofMinutes(10));
        entries.put(RelayHarness.A, new IuDirectory.Partner(RelayHarness.A, "Site a", true,
                RelayHarness.A_KEYS.publicKey(), RelayHarness.A_KEM.publicKey(), RelayHarness.relayUrl
                + "/iu/v1/queues/" + "a".repeat(43)));
        new IuMsgClient(http, Clock.systemUTC()).deposit(QueueAddress.parse(entries.get(RelayHarness.B).address()),
                RelayHarness.A, RelayHarness.A_KEYS, SignedMessage.of(IuTransport.FILE, RelayHarness.A,
                        Codec.json().writeValueAsString(new IuTransport.Fragment(state.transferId(), 0, 1,
                                IuFileUploader.description(state, true).toJson())), Instant.now())
                        .seal(RelayHarness.A_KEYS, RelayHarness.B_KEM.publicKey()));
        TransportEvent.FileOffered offered = await(bEvents, TransportEvent.FileOffered.class);
        b.receiveFile(offered.fileId(), dir.resolve("recu-abandon/" + offered.fileName()));
        Thread.sleep(200);

        // L'émetteur abandonne : ses morceaux sont retirés du relais, le destinataire reçoit un refus définitif.
        uploader.delete(state);
        assertThat(await(bEvents, TransportEvent.FileFailed.class).fileId()).isEqualTo(offered.fileId());
    }
}
