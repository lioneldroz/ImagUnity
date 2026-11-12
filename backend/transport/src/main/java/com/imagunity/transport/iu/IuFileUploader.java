package com.imagunity.transport.iu;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.Chunks;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.FileDescription;
import com.imagunity.protocol.ProtocolException;
import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Envoi d'un paquet par IU-FILE (spécification § 5, 6) : découpage et empreintes, création de tous les morceaux, puis
 * téléversement. L'état ({@link UploadState}) est rendu à chaque morceau terminé pour être enregistré : après une
 * coupure ou un redémarrage, l'envoi reprend aux morceaux non téléversés.
 */
public final class IuFileUploader {

    private final IuFileClient client;
    private final UUID boxId;
    private final HybridKeyPair identity;
    private final Clock clock;
    private final Retry retry;
    private final SecureRandom random = new SecureRandom();

    public IuFileUploader(IuFileClient client, UUID boxId, HybridKeyPair identity, Clock clock, Retry retry) {
        this.client = client;
        this.boxId = boxId;
        this.identity = identity;
        this.clock = clock;
        this.retry = retry;
    }

    /**
     * État d'un envoi, à enregistrer pour reprendre.
     *
     * @param transferId 16 octets aléatoires, base64url
     * @param fileName   nom du paquet
     * @param relay      relais
     * @param chunkSize  taille des morceaux
     * @param size       taille du paquet
     * @param digest     SHA-256 du paquet
     * @param expiresAt  expiration sur le relais
     * @param chunks     morceaux
     * @param paddingKey clé de remplissage du dernier morceau (base64url), ou {@code null} pour des zéros (envoi
     *                   préparé avant la v0.5)
     */
    public record UploadState(String transferId, String fileName, String relay, int chunkSize, long size,
                              String digest, Instant expiresAt, List<ChunkState> chunks, String paddingKey) {

        public UploadState {
            chunks = List.copyOf(chunks);
        }

        public UploadState(String transferId, String fileName, String relay, int chunkSize, long size, String digest,
                           Instant expiresAt, List<ChunkState> chunks) {
            this(transferId, fileName, relay, chunkSize, size, digest, expiresAt, chunks, null);
        }

        byte[] padding() {
            return paddingKey == null ? null : Codec.fromBase64Url(paddingKey, "clé de remplissage");
        }

        public boolean complete() {
            return chunks.stream().allMatch(ChunkState::uploaded);
        }

        UploadState with(ChunkState chunk) {
            List<ChunkState> updated = new ArrayList<>(chunks);
            updated.set(chunk.index(), chunk);
            return new UploadState(transferId, fileName, relay, chunkSize, size, digest, expiresAt, updated,
                    paddingKey);
        }

        public String toJson() {
            return Codec.json().writeValueAsString(this);
        }

        public static UploadState fromJson(String json) {
            return Codec.json().readValue(json, UploadState.class);
        }
    }

    /**
     * Un morceau de l'envoi.
     *
     * @param index        rang
     * @param digest       empreinte du morceau complété
     * @param senderId     identifiant de l'émetteur sur le relais
     * @param senderKey    clé privée de l'émetteur (PKCS#8, base64url)
     * @param recipientId  identifiant de retrait
     * @param retrievalKey clé privée de retrait (PKCS#8, base64url), transmise au destinataire dans la description
     * @param uploaded     vrai une fois téléversé
     */
    public record ChunkState(int index, String digest, String senderId, String senderKey, String recipientId,
                             String retrievalKey, boolean uploaded) {
    }

    /** Découpe le paquet et crée tous ses morceaux sur le relais (requêtes légères, sans données). */
    public UploadState prepare(Path packet, int chunkSize, Duration ttl) throws IOException {
        ChunkLayout layout = ChunkLayout.of(Files.size(packet), chunkSize);
        byte[] padding = new byte[Chunks.PADDING_KEY];
        random.nextBytes(padding);
        List<String> digests = Chunks.digests(packet, layout, padding);
        List<ChunkState> chunks = new ArrayList<>();
        for (int i = 0; i < layout.chunkCount(); i++) {
            KeyPair sender = ed25519();
            KeyPair recipient = ed25519();
            String digest = digests.get(i);
            IuFileClient.Created created = retry.run("Création du morceau " + i, () -> client.create(boxId, identity,
                    chunkSize, digest, sender.getPublic(), List.of(recipient.getPublic()), ttl));
            chunks.add(new ChunkState(i, digest, created.senderId(), Codec.base64Url(sender.getPrivate().getEncoded()),
                    created.recipientIds().getFirst(), Codec.base64Url(recipient.getPrivate().getEncoded()), false));
        }
        byte[] id = new byte[16];
        random.nextBytes(id);
        return new UploadState(Codec.base64Url(id), packet.getFileName().toString(), client.relay().toString(),
                chunkSize, layout.size(), java.util.HexFormat.of().formatHex(sha(packet)), clock.instant().plus(ttl),
                chunks, Codec.base64Url(padding));
    }

    /**
     * Téléverse les morceaux qui ne l'ont pas encore été.
     *
     * @param checkpoint reçoit l'état après chaque morceau téléversé (à enregistrer)
     * @param progress   reçoit le nombre d'octets téléversés depuis le début de l'envoi
     */
    public UploadState upload(Path packet, UploadState state, Consumer<UploadState> checkpoint,
                              LongConsumer progress) throws IOException {
        ChunkLayout layout = new ChunkLayout(state.size(), state.chunkSize(), state.chunks().size());
        if (Files.size(packet) != state.size()) {
            throw new TransportException("Paquet modifié depuis la préparation de l'envoi : " + packet);
        }
        UploadState current = state;
        long done = current.chunks().stream().filter(ChunkState::uploaded).mapToLong(c -> layout.payload(c.index()))
                .sum();
        for (ChunkState chunk : state.chunks()) {
            if (chunk.uploaded()) {
                continue;
            }
            byte[] content = Chunks.read(packet, layout, chunk.index(), state.padding());
            if (!Codec.sha256Hex(content).equals(chunk.digest())) {
                throw new TransportException("Paquet modifié depuis la préparation de l'envoi : " + packet);
            }
            PrivateKey key = privateKey(chunk.senderKey());
            retry.run("Téléversement du morceau " + chunk.index(), () -> {
                client.upload(chunk.senderId(), key, content);
                return null;
            });
            current = current.with(new ChunkState(chunk.index(), chunk.digest(), chunk.senderId(), chunk.senderKey(),
                    chunk.recipientId(), chunk.retrievalKey(), true));
            checkpoint.accept(current);
            done += layout.payload(chunk.index());
            progress.accept(done);
        }
        return current;
    }

    /** Description à transmettre au destinataire, une fois l'envoi téléversé (dans un message chiffré et signé). */
    public static FileDescription description(UploadState state) {
        return description(state, false);
    }

    /**
     * Description à transmettre au destinataire (dans un message chiffré et signé).
     *
     * @param early annoncée dès la création des morceaux, avant la fin de leur téléversement (§ 6.6)
     */
    public static FileDescription description(UploadState state, boolean early) {
        List<FileDescription.Chunk> chunks = state.chunks().stream().map(c -> new FileDescription.Chunk(c.index(),
                c.digest(), List.of(new FileDescription.Replica(state.relay(), c.recipientId(), c.retrievalKey()))))
                .toList();
        return new FileDescription("IU1", state.transferId(), early ? FileDescription.EARLY : FileDescription.COMPLETE,
                state.fileName(),
                state.chunkSize(), state.chunks().size(), state.size(), state.digest(), chunks, state.expiresAt());
    }

    /** Retire du relais tous les morceaux de l'envoi (après l'accusé du destinataire, ou abandon). */
    public void delete(UploadState state) {
        for (ChunkState chunk : state.chunks()) {
            try {
                client.delete(chunk.senderId(), privateKey(chunk.senderKey()));
            } catch (IOException | RelayRefusedException e) {
                // déjà supprimé (acquitté par le destinataire, ou expiré) : sans effet
            }
        }
    }

    private static byte[] sha(Path packet) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (var in = new java.security.DigestInputStream(Files.newInputStream(packet), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    private static KeyPair ed25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static PrivateKey privateKey(String pkcs8) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                    Codec.fromBase64Url(pkcs8, "Clé privée")));
        } catch (GeneralSecurityException | ProtocolException e) {
            throw new TransportException("Clé privée de morceau illisible", e);
        }
    }
}
