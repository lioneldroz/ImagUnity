package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.protocol.FileDescription;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Plusieurs relais indépendants (redondance, « forme 1 ») : chaque box relève une file sur chaque relais, les messages
 * sont déposés sur tous et traités une fois, les morceaux sont copiés sur tous et le destinataire bascule d'un relais
 * à l'autre. Vrais relais en mémoire, données synthétiques.
 */
class IuTransportRelaysTest {

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
            return entries.values().stream().filter(p -> p.addresses().contains(address)).findFirst();
        }
    };
    private final List<IuTransport> opened = new ArrayList<>();
    private final List<ConfigurableApplicationContext> relays = new ArrayList<>();
    private final List<Runnable> stoppers = new ArrayList<>();

    @BeforeAll
    static void startDirectory() throws Exception {
        RelayHarness.start();
    }

    @AfterEach
    void closeAll() {
        opened.forEach(IuTransport::close);
        relays.forEach(ConfigurableApplicationContext::close);
        stoppers.forEach(Runnable::run);
    }

    private record Box(IuTransport transport, List<TransportEvent> events) {

        <T extends TransportEvent> T await(Class<T> type, Predicate<T> condition) throws InterruptedException {
            for (int i = 0; i < 1500; i++) {
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

    private URI relay() throws Exception {
        ConfigurableApplicationContext relay = RelayHarness.relay("relais-" + UUID.randomUUID());
        relays.add(relay);
        return RelayHarness.url(relay);
    }

    private Box box(String name, UUID id, HybridKeyPair keys, HybridKemKeyPair kem, List<URI> relayUrls,
                    int replicas) {
        IuTransport transport = new IuTransport(new IuTransport.Settings(relayUrls, dir.resolve(name),
                Duration.ofHours(1), 2, Duration.ofDays(8), true, 0, replicas), id, keys, kem, directory, http,
                Clock.systemUTC(), quick);
        opened.add(transport);
        List<TransportEvent> events = new CopyOnWriteArrayList<>();
        transport.addListener(events::add);
        Map<String, String> addresses = transport.contactAddresses();
        entries.put(id, new IuDirectory.Partner(id, "Site " + name.toUpperCase(), true, keys.publicKey(),
                kem.publicKey(), addresses.get("iu"), List.copyOf(addresses.values())));
        transport.start();
        return new Box(transport, events);
    }

    private Box a(List<URI> relayUrls, int replicas) {
        return box("a", RelayHarness.A, RelayHarness.A_KEYS, RelayHarness.A_KEM, relayUrls, replicas);
    }

    private Box b(List<URI> relayUrls) {
        return box("b", RelayHarness.B, RelayHarness.B_KEYS, RelayHarness.B_KEM, relayUrls, 0);
    }

    private PeerId[] pair(Box a, Box b) throws InterruptedException {
        a.transport().connect(entries.get(RelayHarness.B).address());
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

    /** Envoi terminé sur toutes ses copies (état enregistré). */
    private static IuStore.Outgoing uploaded(Box box, long fileId) throws InterruptedException {
        for (int i = 0; i < 1500; i++) {
            Optional<IuStore.Outgoing> outgoing = box.transport().store().outgoing(fileId);
            if (outgoing.isPresent() && outgoing.get().announced()) {
                return outgoing.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Envoi " + fileId + " jamais terminé");
    }

    @Test
    void everyMessageAndChunkGoesThroughEveryRelayAndIsHandledOnce() throws Exception {
        URI r1 = relay();
        URI r2 = relay();
        Box a = a(List.of(r1, r2), 0);
        Box b = b(List.of(r1, r2));
        assertThat(a.transport().contactAddresses()).containsOnlyKeys("iu", "iu-2");
        assertThat(a.transport().contactAddresses().get("iu")).startsWith(r1.toString());
        assertThat(a.transport().contactAddresses().get("iu-2")).startsWith(r2.toString());
        PeerId[] peers = pair(a, b);

        a.transport().sendMessage(peers[0], "une fois");
        assertThat(b.await(TransportEvent.MessageReceived.class).text()).isEqualTo("une fois");
        for (int i = 0; i < 100 && !a.transport().store().pending().isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertThat(a.transport().store().pending()).as("déposé sur les deux relais").isEmpty();
        Thread.sleep(300);
        assertThat(b.all(TransportEvent.MessageReceived.class)).as("traité une seule fois").hasSize(1);

        Path packet = randomFile(dir.resolve("envoi/paquet-1.iu1"), 5_000_000);
        long fileId = a.transport().sendFile(peers[0], packet);
        assertThat(a.await(TransportEvent.FileSent.class).fileId()).isEqualTo(fileId);
        IuStore.Outgoing outgoing = uploaded(a, fileId);
        assertThat(outgoing.all()).extracting(IuFileUploader.UploadState::relay)
                .containsExactly(r1.toString(), r2.toString());
        assertThat(outgoing.all()).allSatisfy(s -> assertThat(s.complete()).isTrue());
        FileDescription description = IuFileUploader.description(outgoing.all(), false);
        assertThat(description.chunks()).allSatisfy(c -> assertThat(c.replicas()).hasSize(2));

        TransportEvent.FileOffered offered = b.await(TransportEvent.FileOffered.class);
        b.transport().receiveFile(offered.fileId(), dir.resolve("recu/paquet-1.iu1"));
        TransportEvent.FileReceived received = b.await(TransportEvent.FileReceived.class);
        assertThat(Files.mismatch(packet, received.path())).isEqualTo(-1);
        assertThat(a.all(TransportEvent.FileSent.class)).as("un seul FileSent").hasSize(1);

        a.transport().discardSentFile(fileId);
        for (int i = 0; i < 200 && a.transport().store().outgoing(fileId).isPresent(); i++) {
            Thread.sleep(20);
        }
        assertThat(a.transport().store().outgoing(fileId)).isEmpty();
    }

    @Test
    void theRecipientSwitchesToTheOtherRelayWhenOneStopsAfterTheUpload() throws Exception {
        URI r1 = relay();
        URI r2 = relay();
        Box a = a(List.of(r1, r2), 0);
        Box b = b(List.of(r1, r2));
        PeerId[] peers = pair(a, b);

        Path packet = randomFile(dir.resolve("envoi/paquet-2.iu1"), 3_000_000);
        long fileId = a.transport().sendFile(peers[0], packet);
        uploaded(a, fileId);
        TransportEvent.FileOffered offered = b.await(TransportEvent.FileOffered.class);
        relays.getFirst().close(); // le relais principal tombe avant le retrait

        b.transport().receiveFile(offered.fileId(), dir.resolve("recu/paquet-2.iu1"));
        TransportEvent.FileReceived received = b.await(TransportEvent.FileReceived.class);
        assertThat(Files.mismatch(packet, received.path())).isEqualTo(-1);
        b.transport().sendMessage(peers[1], "accusé");
        assertThat(a.await(TransportEvent.MessageReceived.class).text()).as("par le second relais")
                .isEqualTo("accusé");
    }

    @Test
    void aRelayDownBeforeTheSendIsLeftOutOfIt() throws Exception {
        URI r1 = relay();
        URI r2 = relay();
        Box a = a(List.of(r1, r2), 0);
        Box b = b(List.of(r1, r2));
        PeerId[] peers = pair(a, b);
        relays.getFirst().close();

        Path packet = randomFile(dir.resolve("envoi/paquet-3.iu1"), 2_000_000);
        long fileId = a.transport().sendFile(peers[0], packet);
        IuStore.Outgoing outgoing = uploaded(a, fileId);
        assertThat(outgoing.all()).extracting(IuFileUploader.UploadState::relay).containsExactly(r2.toString());
        TransportEvent.FileOffered offered = b.await(TransportEvent.FileOffered.class);
        b.transport().receiveFile(offered.fileId(), dir.resolve("recu/paquet-3.iu1"));
        assertThat(Files.mismatch(packet, b.await(TransportEvent.FileReceived.class).path())).isEqualTo(-1);

        // Envoi suivant : le relais écarté n'est plus essayé qu'une fois.
        long next = a.transport().sendFile(peers[0], packet);
        assertThat(uploaded(a, next).all()).extracting(IuFileUploader.UploadState::relay)
                .containsExactly(r2.toString());
    }

    /** Relais qui crée les morceaux mais refuse leur contenu : la copie qu'il porte échoue. */
    private URI refusingRelay() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.security.SecureRandom random = new java.security.SecureRandom();
        server.createContext("/iu/v1/chunks", exchange -> {
            int status;
            byte[] body = new byte[0];
            exchange.getRequestBody().readAllBytes();
            switch (exchange.getRequestMethod()) {
                case "POST" -> {
                    byte[] a = new byte[32];
                    byte[] b = new byte[32];
                    random.nextBytes(a);
                    random.nextBytes(b);
                    body = ("{\"senderId\":\"" + com.imagunity.protocol.Codec.base64Url(a)
                            + "\",\"recipientIds\":[\"" + com.imagunity.protocol.Codec.base64Url(b) + "\"]}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    status = 201;
                }
                case "DELETE" -> status = 204;
                default -> status = 400;
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        stoppers.add(() -> server.stop(0));
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void aFailedCopyIsDroppedAndTheOtherCarriesTheFile() throws Exception {
        URI refusing = refusingRelay();
        URI r2 = relay();
        Box a = a(List.of(refusing, r2), 0);
        Box b = b(List.of(r2));
        PeerId[] peers = pair(a, b);

        Path packet = randomFile(dir.resolve("envoi/paquet-5.iu1"), 1_500_000);
        long fileId = a.transport().sendFile(peers[0], packet);
        IuStore.Outgoing outgoing = uploaded(a, fileId);
        assertThat(outgoing.all()).as("la copie refusée est oubliée, l'autre devient la principale")
                .extracting(IuFileUploader.UploadState::relay).containsExactly(r2.toString());
        assertThat(a.all(TransportEvent.FileFailed.class)).isEmpty();
        TransportEvent.FileOffered offered = b.await(TransportEvent.FileOffered.class);
        b.transport().receiveFile(offered.fileId(), dir.resolve("recu/paquet-5.iu1"));
        assertThat(Files.mismatch(packet, b.await(TransportEvent.FileReceived.class).path())).isEqualTo(-1);
    }

    @Test
    void theSendFailsWhenEveryCopyFails() throws Exception {
        URI refusing = refusingRelay();
        URI r2 = relay();
        Box a = a(List.of(refusing), 0);
        Box b = b(List.of(r2));
        entries.put(RelayHarness.B, entries.get(RelayHarness.B));
        Path packet = randomFile(dir.resolve("envoi/paquet-6.iu1"), 500_000);
        // Contact créé localement : l'envoi ne dépend pas de l'appairage.
        IuStore.Contact contact = a.transport().store().addContact(RelayHarness.B, new boolean[1]);
        long fileId = a.transport().sendFile(new PeerId(contact.peerId()), packet);
        assertThat(a.await(TransportEvent.FileFailed.class).fileId()).isEqualTo(fileId);
        assertThat(a.transport().store().outgoing(fileId)).isEmpty();
        assertThat(b.events()).noneMatch(TransportEvent.FileOffered.class::isInstance);
    }

    /** Relais réparti : deux instances sur la même base et le même stockage, derrière un répartiteur. */
    private RoundRobinProxy distributedRelay() throws Exception {
        String database = "reparti-" + UUID.randomUUID();
        Path storage = Files.createTempDirectory("relais-reparti");
        ConfigurableApplicationContext one = RelayHarness.relay(database, storage, "relais-a");
        ConfigurableApplicationContext two = RelayHarness.relay(database, storage, "relais-b");
        relays.add(one);
        relays.add(two);
        RoundRobinProxy proxy = new RoundRobinProxy(List.of(RelayHarness.url(one), RelayHarness.url(two)));
        stoppers.add(proxy::close);
        return proxy;
    }

    @Test
    void aDistributedRelayServesEachRequestFromWhicheverInstance() throws Exception {
        RoundRobinProxy proxy = distributedRelay();
        Box a = a(List.of(proxy.url()), 0);
        Box b = b(List.of(proxy.url()));
        PeerId[] peers = pair(a, b);
        a.transport().sendMessage(peers[0], "par le répartiteur");
        assertThat(b.await(TransportEvent.MessageReceived.class).text()).isEqualTo("par le répartiteur");

        Path packet = randomFile(dir.resolve("envoi/paquet-7.iu1"), 3_000_000);
        a.transport().sendFile(peers[0], packet);
        TransportEvent.FileOffered offered = b.await(TransportEvent.FileOffered.class);
        b.transport().receiveFile(offered.fileId(), dir.resolve("recu/paquet-7.iu1"));
        assertThat(Files.mismatch(packet, b.await(TransportEvent.FileReceived.class).path())).isEqualTo(-1);
        assertThat(proxy.served.values()).as("les deux instances ont servi").hasSize(2)
                .allSatisfy(n -> assertThat(n.get()).isGreaterThan(5));
    }

    @Test
    void aDistributedRelayKeepsWorkingWhenAnInstanceStops() throws Exception {
        RoundRobinProxy proxy = distributedRelay();
        Box a = a(List.of(proxy.url()), 0);
        Box b = b(List.of(proxy.url()));
        PeerId[] peers = pair(a, b);
        Path packet = randomFile(dir.resolve("envoi/paquet-8.iu1"), 3_000_000);
        long fileId = a.transport().sendFile(peers[0], packet);
        uploaded(a, fileId);
        relays.get(relays.size() - 2).close(); // une instance s'arrête : ses morceaux restent sur le stockage
        TransportEvent.FileOffered offered = b.await(TransportEvent.FileOffered.class);
        b.transport().receiveFile(offered.fileId(), dir.resolve("recu/paquet-8.iu1"));
        assertThat(Files.mismatch(packet, b.await(TransportEvent.FileReceived.class).path())).isEqualTo(-1);
        b.transport().sendMessage(peers[1], "accusé");
        assertThat(a.await(TransportEvent.MessageReceived.class).text()).isEqualTo("accusé");
    }

    @Test
    void theNumberOfCopiesCanBeLimited() throws Exception {
        URI r1 = relay();
        URI r2 = relay();
        Box a = a(List.of(r1, r2), 1);
        Box b = b(List.of(r1, r2));
        PeerId[] peers = pair(a, b);

        Path packet = randomFile(dir.resolve("envoi/paquet-4.iu1"), 1_000_000);
        long fileId = a.transport().sendFile(peers[0], packet);
        assertThat(uploaded(a, fileId).all()).extracting(IuFileUploader.UploadState::relay)
                .containsExactly(r1.toString());
        TransportEvent.FileOffered offered = b.await(TransportEvent.FileOffered.class);
        b.transport().receiveFile(offered.fileId(), dir.resolve("recu/paquet-4.iu1"));
        assertThat(Files.mismatch(packet, b.await(TransportEvent.FileReceived.class).path())).isEqualTo(-1);
    }
}
