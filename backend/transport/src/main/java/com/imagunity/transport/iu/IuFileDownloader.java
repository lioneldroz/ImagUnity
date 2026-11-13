package com.imagunity.transport.iu;

import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.Chunks;
import com.imagunity.protocol.FileDescription;
import com.imagunity.protocol.ProtocolException;
import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * Téléchargement d'un paquet décrit par une {@link FileDescription} : chaque morceau est téléchargé (avec reprise),
 * vérifié, écrit à sa place, puis acquitté auprès du relais ; le paquet réassemblé est vérifié en entier.
 */
public final class IuFileDownloader {

    /** Attente longue demandée au relais pour un morceau pas encore téléversé. */
    static final Duration WAIT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final Clock clock;
    private final Retry retry;
    private final Map<String, IuFileClient> clients = new HashMap<>();
    /** Relais en échec récent : essayés en dernier. */
    private final Map<String, java.time.Instant> failing = new java.util.concurrent.ConcurrentHashMap<>();
    static final Duration FAILING_FOR = Duration.ofMinutes(1);

    public IuFileDownloader(HttpClient http, Clock clock, Retry retry) {
        this.http = http;
        this.clock = clock;
        this.retry = retry;
    }

    /**
     * Télécharge le paquet dans {@code target}.
     *
     * @param done      morceaux déjà écrits dans {@code target} (reprise), à ne pas retélécharger
     * @param onChunk   reçoit le rang de chaque morceau écrit et acquitté (à enregistrer pour reprendre)
     * @param progress  reçoit le nombre d'octets utiles reçus
     * @throws TransportException si un morceau ne peut pas être obtenu, ou si le paquet réassemblé est altéré
     */
    public void download(FileDescription description, Path target, Set<Integer> done, IntConsumer onChunk,
                         LongConsumer progress) throws IOException {
        ChunkLayout layout = description.layout();
        long received = done.stream().mapToLong(layout::payload).sum();
        for (FileDescription.Chunk chunk : description.chunks()) {
            if (done.contains(chunk.index())) {
                continue;
            }
            Fetched fetched = fetch(description, layout, chunk, target);
            IuFileClient client = fetched.client();
            FileDescription.Replica replica = fetched.replica();
            retry.run("Acquittement du morceau " + chunk.index(), () -> {
                client.ack(replica.recipientId(), replica.privateRetrievalKey());
                return null;
            });
            onChunk.accept(chunk.index());
            received += layout.payload(chunk.index());
            progress.accept(received);
        }
        if (Files.size(target) != layout.size() || !sha256(target).equals(description.digest())) {
            throw new TransportException("Paquet " + description.fileName()
                    + " réassemblé différent de la description (taille ou empreinte)");
        }
    }

    private record Fetched(IuFileClient client, FileDescription.Replica replica) {
    }

    /**
     * Obtient un morceau et l'écrit à sa place, en basculant d'une réplique à l'autre : chaque tour essaie une fois
     * chaque réplique encore valable, dans l'ordre de la description ; une réplique refusée définitivement (retirée,
     * expirée) ou altérée est écartée. Entre deux tours, attente croissante comme {@link Retry}, jusqu'à sa patience.
     * Avec une seule réplique, le comportement est celui de {@link Retry#run}.
     */
    private Fetched fetch(FileDescription description, ChunkLayout layout, FileDescription.Chunk chunk, Path target)
            throws IOException {
        java.util.List<FileDescription.Replica> replicas = new java.util.ArrayList<>(chunk.replicas());
        java.time.Instant now = clock.instant();
        // Stable : l'ordre de la description est gardé, les relais en échec récent passent après les autres.
        replicas.sort(java.util.Comparator.comparing(r -> failing.getOrDefault(r.relay(), java.time.Instant.MIN)
                .isAfter(now)));
        Duration waited = Duration.ZERO;
        Duration delay = Duration.ofSeconds(1);
        RuntimeException refused = null;
        Exception failure = null;
        while (true) {
            for (java.util.Iterator<FileDescription.Replica> it = replicas.iterator(); it.hasNext(); ) {
                FileDescription.Replica replica = it.next();
                IuFileClient client = clients.computeIfAbsent(replica.relay(),
                        relay -> new IuFileClient(URI.create(relay), http, clock));
                byte[] content;
                try {
                    content = client.download(replica.recipientId(), replica.privateRetrievalKey(),
                            layout.chunkSize(), WAIT);
                } catch (IOException e) {
                    failure = e;
                    failing.put(replica.relay(), clock.instant().plus(FAILING_FOR));
                    continue;
                } catch (RelayRefusedException e) {
                    if (e.retryable()) {
                        failure = e;
                        if (e.status() != 425) {
                            failing.put(replica.relay(), clock.instant().plus(FAILING_FOR));
                        }
                    } else {
                        refused = e;
                        it.remove();
                    }
                    continue;
                }
                try {
                    Chunks.write(target, layout, chunk.index(), content, chunk.digest());
                    failing.remove(replica.relay());
                    return new Fetched(client, replica);
                } catch (ProtocolException e) {
                    refused = new TransportException("Paquet " + description.fileName() + " : " + e.getMessage(), e);
                    it.remove();
                }
            }
            if (replicas.isEmpty()) {
                throw refused;
            }
            if (waited.compareTo(retry.patience()) >= 0) {
                throw new TransportException("Téléchargement du morceau " + chunk.index() + " impossible : "
                        + (failure == null ? "aucune réplique" : Retry.reason(failure)), failure);
            }
            try {
                retry.sleeper().sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransportException("Téléchargement du morceau " + chunk.index() + " interrompu", e);
            }
            waited = waited.plus(delay);
            delay = delay.multipliedBy(2).compareTo(retry.maxDelay()) > 0 ? retry.maxDelay() : delay.multipliedBy(2);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
