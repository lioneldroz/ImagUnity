package com.imagunity.transport.iu;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.SignedRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Client IU-FILE (spécification § 6.2) : un appel par opération, requêtes signées. Les erreurs réseau remontent en
 * {@link IOException} (à réessayer) ; les refus du relais en {@link RelayRefusedException}.
 */
public final class IuFileClient {

    private static final String BASE = "/iu/v1/chunks";

    private final URI relay;
    private final HttpClient http;
    private final Clock clock;

    /** @param relay adresse du relais, par exemple {@code https://relais-1.org.example} */
    public IuFileClient(URI relay, HttpClient http, Clock clock) {
        this.relay = URI.create(relay.toString().replaceAll("/+$", ""));
        this.http = http;
        this.clock = clock;
    }

    public URI relay() {
        return relay;
    }

    /**
     * Morceau créé.
     *
     * @param senderId     identifiant de l'émetteur
     * @param recipientIds identifiants de retrait, dans l'ordre des clés de retrait
     */
    public record Created(String senderId, List<String> recipientIds) {
    }

    /** État d'un morceau vu par l'émetteur. */
    public record State(boolean uploaded, int recipientsAcked, int recipients) {
    }

    /** Crée un morceau (requête de dépôt, signée par l'identité de la box). */
    public Created create(UUID boxId, HybridKeyPair identity, int size, String digest, PublicKey senderKey,
                          List<PublicKey> recipientKeys, Duration ttl) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("suite", "IU1");
        body.put("size", size);
        body.put("digest", digest);
        body.put("senderKey", Codec.base64Url(senderKey.getEncoded()));
        body.put("recipientKeys", recipientKeys.stream().map(k -> Codec.base64Url(k.getEncoded())).toList());
        body.put("ttl", ttl.toSeconds());
        byte[] json = Codec.json().writeValueAsBytes(body);
        SignedRequest signed = SignedRequest.of("POST", BASE, json, clock.instant());
        HttpRequest request = builder(BASE, signed, boxId.toString(), signed.signDeposit(identity))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();
        JsonNode created = Codec.json().readTree(send(request, 201));
        return new Created(created.get("senderId").asString(),
                created.get("recipientIds").valueStream().map(JsonNode::asString).toList());
    }

    /** Téléverse le contenu d'un morceau. */
    public void upload(String senderId, PrivateKey senderKey, byte[] chunk) throws IOException {
        String path = BASE + "/" + senderId;
        SignedRequest signed = SignedRequest.of("PUT", path, chunk, clock.instant());
        send(builder(path, signed, senderId, signed.signCapability(senderKey))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build(), 204);
    }

    /**
     * Télécharge un morceau ; une coupure en cours de route reprend où elle s'était arrêtée ({@code Range}).
     *
     * @param wait attente longue si le morceau n'est pas encore téléversé (plafonnée par le relais)
     * @throws RelayRefusedException 425 si le morceau n'est toujours pas téléversé
     */
    public byte[] download(String recipientId, PrivateKey key, int expectedSize, Duration wait) throws IOException {
        String path = BASE + "/" + recipientId;
        ByteArrayOutputStream received = new ByteArrayOutputStream(expectedSize);
        int interruptions = 0;
        while (true) {
            SignedRequest signed = SignedRequest.of("GET", path, null, clock.instant());
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(relay + path + "?wait="
                    + wait.toSeconds())).timeout(wait.plusSeconds(60));
            headers(request, signed, recipientId, signed.signCapability(key));
            if (received.size() > 0) {
                request.header("Range", "bytes=" + received.size() + "-");
            }
            HttpResponse<InputStream> response = exchange(request.GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int expected = received.size() > 0 ? 206 : 200;
                if (response.statusCode() != expected) {
                    throw refused(response.statusCode(), body.readAllBytes());
                }
                body.transferTo(received);
                return received.toByteArray();
            } catch (IOException e) {
                if (++interruptions > 5) {
                    throw e;
                }
                // reprise au même endroit, avec une nouvelle requête signée
            }
        }
    }

    /** Acquitte un retrait : le relais révoque l'accès et supprime le morceau quand tous ont acquitté. */
    public void ack(String recipientId, PrivateKey key) throws IOException {
        String path = BASE + "/" + recipientId + "/ack";
        SignedRequest signed = SignedRequest.of("POST", path, null, clock.instant());
        send(builder(path, signed, recipientId, signed.signCapability(key))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), 204);
    }

    /** Supprime un morceau (émetteur). */
    public void delete(String senderId, PrivateKey key) throws IOException {
        String path = BASE + "/" + senderId;
        SignedRequest signed = SignedRequest.of("DELETE", path, null, clock.instant());
        send(builder(path, signed, senderId, signed.signCapability(key)).DELETE().build(), 204);
    }

    /** État d'un morceau (émetteur). */
    public State state(String senderId, PrivateKey key) throws IOException {
        String path = BASE + "/" + senderId;
        SignedRequest signed = SignedRequest.of("GET", path, null, clock.instant());
        JsonNode state = Codec.json().readTree(send(builder(path, signed, senderId, signed.signCapability(key))
                .GET().build(), 200));
        return new State(state.get("uploaded").asBoolean(), state.get("recipientsAcked").asInt(),
                state.get("recipients").asInt());
    }

    private HttpRequest.Builder builder(String path, SignedRequest signed, String keyId, String signature) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(relay + path)).timeout(Duration.ofMinutes(5));
        return headers(builder, signed, keyId, signature);
    }

    private static HttpRequest.Builder headers(HttpRequest.Builder builder, SignedRequest signed, String keyId,
                                               String signature) {
        return IuFileClientHeaders.apply(builder, signed, keyId, signature);
    }

    private byte[] send(HttpRequest request, int expected) throws IOException {
        HttpResponse<byte[]> response = exchange(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != expected) {
            throw refused(response.statusCode(), response.body());
        }
        return response.body();
    }

    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Requête interrompue", e);
        }
    }

    /** Refus du relais, avec le détail de sa réponse (problème JSON) quand il est lisible. */
    static RelayRefusedException refused(int status, byte[] body) {
        Function<byte[], String> detail = bytes -> {
            try {
                JsonNode node = Codec.json().readTree(bytes);
                return node.path("detail").asString(new String(bytes, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        };
        return new RelayRefusedException(status, detail.apply(body).strip());
    }
}
