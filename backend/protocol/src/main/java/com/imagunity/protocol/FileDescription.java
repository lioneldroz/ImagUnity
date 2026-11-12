package com.imagunity.protocol;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Description d'un fichier déposé sur les relais (spécification § 7) : tout ce qu'il faut au destinataire pour
 * retrouver et vérifier le paquet. Elle contient des clés de retrait : elle ne voyage que dans un message chiffré et
 * signé pour le destinataire ({@link SignedMessage}).
 *
 * @param suite      {@code IU1}
 * @param transferId 16 octets aléatoires, base64url
 * @param mode       {@code complete} : annoncé une fois téléversé ; {@code early} : annoncé dès la création des
 *                   morceaux, téléchargeables au fil de leur téléversement (attente longue, § 6.6)
 * @param fileName   nom du paquet (pour le destinataire)
 * @param chunkSize  taille des morceaux
 * @param chunkCount nombre de morceaux
 * @param size       taille réelle du paquet
 * @param digest     SHA-256 du paquet, en hexadécimal
 * @param chunks     morceaux, dans l'ordre
 * @param expiresAt  expiration sur les relais
 */
public record FileDescription(String suite, String transferId, String mode, String fileName, int chunkSize,
                              int chunkCount, long size, String digest, List<Chunk> chunks, Instant expiresAt) {

    public static final String COMPLETE = "complete";
    public static final String EARLY = "early";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ID16 = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern ID32 = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public FileDescription {
        if (!"IU1".equals(suite)) {
            throw new ProtocolException("Suite non prise en charge : " + suite);
        }
        require(transferId != null && ID16.matcher(transferId).matches(), "identifiant de transfert");
        require(COMPLETE.equals(mode) || EARLY.equals(mode), "mode « " + mode + " »");
        require(fileName != null && NAME.matcher(fileName).matches() && !fileName.contains(".."), "nom de fichier");
        ChunkLayout layout = new ChunkLayout(size, chunkSize, chunkCount);
        require(digest != null && SHA256.matcher(digest).matches(), "empreinte du paquet");
        Objects.requireNonNull(expiresAt, "expiresAt");
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        require(chunks.size() == layout.chunkCount(), "nombre de morceaux");
        for (int i = 0; i < chunks.size(); i++) {
            require(chunks.get(i).index() == i, "ordre des morceaux");
        }
    }

    /** Vrai si le fichier est annoncé avant la fin de son téléversement. */
    public boolean early() {
        return EARLY.equals(mode);
    }

    /** Découpage du paquet décrit. */
    public ChunkLayout layout() {
        return new ChunkLayout(size, chunkSize, chunkCount);
    }

    public String toJson() {
        return Codec.toJson(this);
    }

    public static FileDescription fromJson(String json) {
        return Codec.fromJson(json, FileDescription.class, "Description de fichier");
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new ProtocolException("Description de fichier invalide : " + what);
        }
    }

    /**
     * Un morceau et ses répliques.
     *
     * @param index    rang, à partir de 0
     * @param digest   SHA-256 du morceau complété, en hexadécimal
     * @param replicas relais qui le détiennent, au moins un
     */
    public record Chunk(int index, String digest, List<Replica> replicas) {

        public Chunk {
            require(digest != null && SHA256.matcher(digest).matches(), "empreinte du morceau " + index);
            replicas = replicas == null ? List.of() : List.copyOf(replicas);
            require(!replicas.isEmpty(), "morceau " + index + " sans relais");
        }
    }

    /**
     * Une réplique d'un morceau sur un relais.
     *
     * @param relay        adresse du relais ({@code https://…}, {@code http://…} pour les essais)
     * @param recipientId  identifiant de retrait, 32 octets en base64url
     * @param retrievalKey clé privée de retrait Ed25519 (PKCS#8, base64url)
     */
    public record Replica(String relay, String recipientId, String retrievalKey) {

        public Replica {
            URI uri;
            try {
                uri = URI.create(Objects.requireNonNull(relay, "relay"));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ProtocolException("Description de fichier invalide : adresse de relais", e);
            }
            require(("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null, "adresse de relais");
            require(recipientId != null && ID32.matcher(recipientId).matches(), "identifiant de retrait");
            parseKey(retrievalKey);
        }

        /** Clé privée de retrait. */
        public PrivateKey privateRetrievalKey() {
            return parseKey(retrievalKey);
        }

        private static PrivateKey parseKey(String encoded) {
            try {
                return KeyFactory.getInstance("Ed25519").generatePrivate(
                        new PKCS8EncodedKeySpec(Codec.fromBase64Url(encoded, "Clé de retrait")));
            } catch (GeneralSecurityException | ProtocolException e) {
                throw new ProtocolException("Description de fichier invalide : clé de retrait", e);
            }
        }
    }
}
