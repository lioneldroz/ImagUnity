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
            FileDescription.Replica replica = chunk.replicas().getFirst();
            IuFileClient client = clients.computeIfAbsent(replica.relay(),
                    relay -> new IuFileClient(URI.create(relay), http, clock));
            byte[] content = retry.run("Téléchargement du morceau " + chunk.index(), () -> client.download(
                    replica.recipientId(), replica.privateRetrievalKey(), layout.chunkSize(), WAIT));
            try {
                Chunks.write(target, layout, chunk.index(), content, chunk.digest());
            } catch (ProtocolException e) {
                throw new TransportException("Paquet " + description.fileName() + " : " + e.getMessage(), e);
            }
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
