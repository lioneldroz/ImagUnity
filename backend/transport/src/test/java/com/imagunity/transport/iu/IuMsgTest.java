package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.KeyFiles;
import com.imagunity.protocol.MessageEnvelope;
import com.imagunity.protocol.SignedMessage;
import com.imagunity.transport.TransportException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** IU-MSG de bout en bout contre un vrai relais : deux box, A écrit à B, B relève. */
class IuMsgTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final IuMsgClient client = new IuMsgClient(http, Clock.systemUTC());
    private final Retry quick = new Retry(Duration.ofSeconds(3), Duration.ofSeconds(1), d -> { });
    private static final Function<UUID, Optional<HybridPublicKey>> DIRECTORY = id ->
            id.equals(RelayHarness.A) ? Optional.of(RelayHarness.A_KEYS.publicKey())
                    : id.equals(RelayHarness.B) ? Optional.of(RelayHarness.B_KEYS.publicKey()) : Optional.empty();

    @BeforeAll
    static void startRelay() throws Exception {
        RelayHarness.start();
    }

    private IuMailbox.Queue queueOfB() throws Exception {
        return IuMailbox.create(client, RelayHarness.relayUrl, RelayHarness.B, RelayHarness.B_KEYS);
    }

    private IuMessenger messengerOfA() {
        return new IuMessenger(client, RelayHarness.A, RelayHarness.A_KEYS, Clock.systemUTC(), quick);
    }

    @Test
    void messagesWaitForAnOfflineBoxAndAreDeliveredOnce() throws Exception {
        IuMailbox.Queue queue = queueOfB();
        IuMessenger a = messengerOfA();
        // B est « éteinte » : A dépose quand même.
        SignedMessage first = a.send(queue.address(), RelayHarness.B, RelayHarness.B_KEM.publicKey(), "file.announce", "annonce 1");
        a.send(queue.address(), RelayHarness.B, RelayHarness.B_KEM.publicKey(), "file.announce", "annonce 2");
        a.send(queue.address(), RelayHarness.B, RelayHarness.B_KEM.publicKey(), "ping", "liaison");

        List<SignedMessage> delivered = new CopyOnWriteArrayList<>();
        IuMailbox mailbox = new IuMailbox(client, queue, RelayHarness.B_KEM, RelayHarness.B, DIRECTORY, delivered::add,
                Clock.systemUTC());
        assertThat(mailbox.pollOnce(Duration.ZERO)).isEqualTo(3);

        assertThat(delivered).extracting(SignedMessage::body).containsExactly("annonce 1", "annonce 2", "liaison");
        assertThat(delivered).allSatisfy(m -> assertThat(m.boxId()).isEqualTo(RelayHarness.A));
        assertThat(delivered.getFirst()).isEqualTo(first);
        assertThat(mailbox.pollOnce(Duration.ZERO)).as("acquittés : plus rien").isZero();

        // Même enveloppe déposée deux fois (rejeu) : remise une seule fois.
        byte[] envelope = SignedMessage.of("file.ack", RelayHarness.A, RelayHarness.B, "accusé", java.time.Instant.now())
                .seal(RelayHarness.A_KEYS, RelayHarness.B_KEM.publicKey());
        client.deposit(queue.address(), RelayHarness.A, RelayHarness.A_KEYS, envelope);
        client.deposit(queue.address(), RelayHarness.A, RelayHarness.A_KEYS, envelope);
        assertThat(mailbox.pollOnce(Duration.ZERO)).isEqualTo(1);
        assertThat(mailbox.pollOnce(Duration.ZERO)).isZero();
        assertThat(mailbox.queue()).isEqualTo(queue);
    }

    @Test
    void unknownSendersAreKeptBadMessagesDroppedAndFailedHandlingRetried() throws Exception {
        IuMailbox.Queue queue = queueOfB();
        messengerOfA().send(queue.address(), RelayHarness.B, RelayHarness.B_KEM.publicKey(), "ping", "un");

        // Annuaire pas encore synchronisé : le message reste sur le relais.
        List<SignedMessage> delivered = new CopyOnWriteArrayList<>();
        assertThat(new IuMailbox(client, queue, RelayHarness.B_KEM, RelayHarness.B, id -> Optional.empty(), delivered::add,
                Clock.systemUTC()).pollOnce(Duration.ZERO)).isZero();
        // Traitement en échec : pas d'acquittement, remis à la relève suivante.
        IuMailbox failing = new IuMailbox(client, queue, RelayHarness.B_KEM, RelayHarness.B, DIRECTORY, m -> {
            throw new IllegalStateException("base indisponible");
        }, Clock.systemUTC());
        assertThatThrownBy(() -> failing.pollOnce(Duration.ZERO)).hasMessageContaining("base indisponible");
        IuMailbox mailbox = new IuMailbox(client, queue, RelayHarness.B_KEM, RelayHarness.B, DIRECTORY, delivered::add,
                Clock.systemUTC());
        assertThat(mailbox.pollOnce(Duration.ZERO)).isEqualTo(1);

        // Messages illisibles, pour une autre box, ou mal signés : écartés.
        client.deposit(queue.address(), RelayHarness.A, RelayHarness.A_KEYS, "pas une enveloppe".getBytes());
        client.deposit(queue.address(), RelayHarness.A, RelayHarness.A_KEYS,
                MessageEnvelope.seal("x".getBytes(), HybridKemKeyPair.generate().publicKey()));
        client.deposit(queue.address(), RelayHarness.A, RelayHarness.A_KEYS,
                SignedMessage.of("ping", RelayHarness.A, RelayHarness.B, "falsifié", java.time.Instant.now())
                        .seal(HybridKeyPair.generate(), RelayHarness.B_KEM.publicKey()));
        assertThat(mailbox.pollOnce(Duration.ZERO)).isZero();
        assertThat(client.receive(RelayHarness.relayUrl, queue.recvId(), queue.privateKey(), Duration.ZERO))
                .as("écartés et acquittés").isEmpty();
    }

    @Test
    void unknownSendersCannotBlockTheQueueForever() throws Exception {
        IuMailbox.Queue queue = queueOfB();
        messengerOfA().send(queue.address(), RelayHarness.B, RelayHarness.B_KEM.publicKey(), "ping", "inconnu");
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.now());
        Clock movable = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return now.get();
            }
        };
        List<SignedMessage> delivered = new CopyOnWriteArrayList<>();
        IuMailbox mailbox = new IuMailbox(client, queue, RelayHarness.B_KEM, RelayHarness.B, id -> Optional.empty(),
                delivered::add, movable);
        assertThat(mailbox.pollOnce(Duration.ZERO)).isZero();
        assertThat(client.receive(RelayHarness.relayUrl, queue.recvId(), queue.privateKey(), Duration.ZERO))
                .as("gardé pour plus tard").hasSize(1);
        now.set(now.get().plus(IuMailbox.UNKNOWN_SENDER_GRACE).plusSeconds(1));
        assertThat(mailbox.pollOnce(Duration.ZERO)).isZero();
        assertThat(client.receive(RelayHarness.relayUrl, queue.recvId(), queue.privateKey(), Duration.ZERO))
                .as("écarté après le délai").isEmpty();
        assertThat(delivered).isEmpty();
    }

    @Test
    void mailboxPollsContinuouslyUntilClosed() throws Exception {
        IuMailbox.Queue queue = queueOfB();
        List<SignedMessage> delivered = new CopyOnWriteArrayList<>();
        try (IuMailbox mailbox = new IuMailbox(client, queue, RelayHarness.B_KEM, RelayHarness.B, DIRECTORY, delivered::add,
                Clock.systemUTC())) {
            mailbox.start(d -> Thread.sleep(10));
            messengerOfA().send(queue.address(), RelayHarness.B, RelayHarness.B_KEM.publicKey(), "ping", "en direct");
            for (int i = 0; i < 100 && delivered.isEmpty(); i++) {
                Thread.sleep(50);
            }
        }
        assertThat(delivered).extracting(SignedMessage::body).containsExactly("en direct");

        // Relève impossible (file inconnue) : la boucle réessaie sans s'arrêter, puis se ferme.
        IuMailbox.Queue unknown = new IuMailbox.Queue(queue.relay(), queue.sendId(), "x".repeat(43), queue.queueKey());
        try (IuMailbox broken = new IuMailbox(client, unknown, RelayHarness.B_KEM, RelayHarness.B, DIRECTORY, delivered::add,
                Clock.systemUTC())) {
            broken.start(d -> Thread.sleep(5));
            Thread.sleep(100);
        }
        new IuMailbox(client, queue, RelayHarness.B_KEM, RelayHarness.B, DIRECTORY, delivered::add, Clock.systemUTC()).close();
    }

    @Test
    void addressesQueuesAndRotation() throws Exception {
        IuMailbox.Queue queue = queueOfB();
        assertThat(QueueAddress.parse(queue.address().toString())).isEqualTo(queue.address());
        assertThat(IuMailbox.Queue.fromJson(queue.toJson())).isEqualTo(queue);
        assertThatThrownBy(() -> QueueAddress.parse("https://relais/iu/v1/queues/court"))
                .isInstanceOf(TransportException.class);
        assertThatThrownBy(() -> QueueAddress.parse(null)).isInstanceOf(TransportException.class);

        IuMsgClient.Ids rotated = client.rotate(RelayHarness.relayUrl, queue.recvId(), queue.privateKey());
        assertThat(rotated.sendId()).isNotEqualTo(queue.sendId());
        assertThatThrownBy(() -> messengerOfA().send(queue.address(), RelayHarness.B, RelayHarness.B_KEM.publicKey(), "ping", "x"))
                .isInstanceOfSatisfying(RelayRefusedException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> client.ack(RelayHarness.relayUrl, rotated.recvId(), queue.privateKey(), "inconnu"))
                .isInstanceOf(RelayRefusedException.class);
        IuMessenger stranger = new IuMessenger(client, UUID.randomUUID(), HybridKeyPair.generate(), Clock.systemUTC(),
                quick);
        assertThatThrownBy(() -> stranger.send(new QueueAddress(RelayHarness.relayUrl, rotated.sendId()),
                RelayHarness.B, RelayHarness.B_KEM.publicKey(), "ping", "x"))
                .isInstanceOfSatisfying(RelayRefusedException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> client.receive(RelayHarness.relayUrl, rotated.recvId(),
                HybridKeyPair.generate().ed25519().getPrivate(), Duration.ZERO)).isInstanceOf(RelayRefusedException.class);
    }

    @Test
    void commandLineToolCreatesSendsAndReceives() throws Exception {
        String phrase = "phrase des box d'essai";
        Path a = dir.resolve("a.json");
        Path b = dir.resolve("b.json");
        KeyFiles.writePrivate(a, RelayHarness.A_KEYS, phrase.toCharArray());
        KeyFiles.writeEncryptionPrivate(Path.of(a + ".chiffrement"), RelayHarness.A_KEM, phrase.toCharArray());
        Files.writeString(Path.of(a + ".id"), RelayHarness.A.toString());
        KeyFiles.writePrivate(b, RelayHarness.B_KEYS, phrase.toCharArray());
        KeyFiles.writeEncryptionPrivate(Path.of(b + ".chiffrement"), RelayHarness.B_KEM, phrase.toCharArray());
        Files.writeString(Path.of(b + ".id"), RelayHarness.B.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        IuFileTool tool = new IuFileTool(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("IMAGUNITY_PHRASE_BOX", phrase), http, quick);
        Path queue = dir.resolve("file-b.json");

        assertThat(tool.run(new String[] {"file-creer", "--relais", RelayHarness.relayUrl.toString(), "--cles",
            b.toString(), "--sortie", queue.toString()})).as(err.toString(StandardCharsets.UTF_8)).isZero();
        String address = out.toString(StandardCharsets.UTF_8).replaceAll("(?s).*annuaire : ", "").strip();
        assertThat(tool.run(new String[] {"message-envoyer", "--adresse", address, "--cles", a.toString(),
            "--destinataire-box", RelayHarness.B.toString(), "--destinataire-chiffrement", b + ".chiffrement",
            "--type", "ping", "--corps", "bonjour B"}))
                .as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(tool.run(new String[] {"message-relever", "--file", queue.toString(), "--cles", b.toString(),
            "--annuaire", RelayHarness.directoryUrl.toString(), "--organisation", RelayHarness.organizationKey.toString(),
            "--attente", "0"})).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Reçu de " + RelayHarness.A + " : ping « bonjour B »")
                .contains("1 message(s) relevé(s)");
    }
}
