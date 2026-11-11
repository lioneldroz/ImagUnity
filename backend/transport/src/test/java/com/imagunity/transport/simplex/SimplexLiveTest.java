package com.imagunity.transport.simplex;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.transport.Invitation;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Essai de la phase 1 sur deux vrais clients simplex-chat (box A et box B).
 *
 * <p>Ne s'exécute que si les variables d'environnement sont définies, par exemple :
 * <pre>
 * IMAGUNITY_SIMPLEX_A=ws://localhost:5225
 * IMAGUNITY_SIMPLEX_B=ws://localhost:5226
 * IMAGUNITY_SIMPLEX_FILE_MB=50      (facultatif, 5 par défaut)
 * </pre>
 * Les deux clients doivent partager le système de fichiers du test (même machine).
 * Fichiers de test aléatoires uniquement : aucune donnée de patient.
 */
@EnabledIfEnvironmentVariable(named = "IMAGUNITY_SIMPLEX_A", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IMAGUNITY_SIMPLEX_B", matches = ".+")
class SimplexLiveTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofMinutes(2);

    private final BlockingQueue<TransportEvent> eventsA = new LinkedBlockingQueue<>();
    private final BlockingQueue<TransportEvent> eventsB = new LinkedBlockingQueue<>();
    private SimplexTransport boxA;
    private SimplexTransport boxB;

    @AfterEach
    void tearDown() {
        if (boxA != null) {
            boxA.close();
        }
        if (boxB != null) {
            boxB.close();
        }
    }

    @Test
    void pairsSendsMessageAndTransfersFileIntact(@TempDir Path dir) throws Exception {
        boxA = open("IMAGUNITY_SIMPLEX_A", eventsA);
        boxB = open("IMAGUNITY_SIMPLEX_B", eventsB);

        // 1. Appairage par lien d'invitation à usage unique.
        long t0 = System.nanoTime();
        Invitation invitation = boxA.createInvitation();
        boxB.acceptInvitation(invitation.link());
        var connectedOnA = await(eventsA, TransportEvent.PeerConnected.class, e -> true, CONNECT_TIMEOUT);
        var connectedOnB = await(eventsB, TransportEvent.PeerConnected.class, e -> true, CONNECT_TIMEOUT);
        report("appairage", t0);
        PeerId bSeenFromA = connectedOnA.peer();

        // 2. Message de signalisation A -> B.
        t0 = System.nanoTime();
        boxA.sendMessage(bSeenFromA, "imagunity:ping");
        var message = await(eventsB, TransportEvent.MessageReceived.class,
                e -> e.text().equals("imagunity:ping"), CONNECT_TIMEOUT);
        assertThat(message.peer()).isEqualTo(connectedOnB.peer());
        report("message SMP", t0);

        // 3. Fichier A -> B par XFTP, intégrité vérifiée par SHA-256.
        long sizeMb = Long.parseLong(System.getenv().getOrDefault("IMAGUNITY_SIMPLEX_FILE_MB", "5"));
        Path source = randomFile(dir.resolve("source.bin"), sizeMb * 1024 * 1024);
        Duration transferTimeout = Duration.ofMinutes(5 + sizeMb / 10);

        t0 = System.nanoTime();
        long sentId = boxA.sendFile(bSeenFromA, source);
        var offer = await(eventsB, TransportEvent.FileOffered.class, e -> true, transferTimeout);
        assertThat(offer.size()).isEqualTo(Files.size(source));
        Path target = dir.resolve("recu.bin");
        boxB.receiveFile(offer.fileId(), target);
        var received = await(eventsB, TransportEvent.FileReceived.class,
                e -> e.fileId() == offer.fileId(), transferTimeout);
        await(eventsA, TransportEvent.FileSent.class, e -> e.fileId() == sentId, transferTimeout);
        report("fichier " + sizeMb + " Mo", t0);

        Path receivedPath = received.path() != null ? received.path() : target;
        assertThat(sha256(receivedPath)).isEqualTo(sha256(source));
    }

    private static SimplexTransport open(String variable, BlockingQueue<TransportEvent> sink) {
        SimplexChatClient client = SimplexChatClient.connect(URI.create(System.getenv(variable)), COMMAND_TIMEOUT);
        SimplexTransport transport = new SimplexTransport(client);
        transport.addListener(sink::add);
        return transport;
    }

    /** Attend le premier événement du type voulu qui satisfait la condition ; les autres sont écartés. */
    private static <T extends TransportEvent> T await(BlockingQueue<TransportEvent> events, Class<T> type,
                                                      Predicate<T> condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            TransportEvent event = remaining > 0 ? events.poll(remaining, TimeUnit.NANOSECONDS) : null;
            if (event == null) {
                throw new AssertionError("Pas d'événement " + type.getSimpleName() + " après " + timeout);
            }
            if (event instanceof TransportEvent.FileFailed failed) {
                throw new AssertionError("Transfert en échec : " + failed);
            }
            if (type.isInstance(event) && condition.test(type.cast(event))) {
                return type.cast(event);
            }
        }
    }

    private static void report(String step, long startNanos) {
        System.out.printf("[phase 1] %-16s %6d ms%n", step, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static Path randomFile(Path path, long size) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        Random random = new Random(42);
        try (OutputStream out = Files.newOutputStream(path)) {
            for (long written = 0; written < size; written += buffer.length) {
                random.nextBytes(buffer);
                out.write(buffer, 0, (int) Math.min(buffer.length, size - written));
            }
        }
        return path;
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
