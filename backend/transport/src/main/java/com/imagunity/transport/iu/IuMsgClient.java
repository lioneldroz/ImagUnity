package com.imagunity.transport.iu;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.SignedRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Client IU-MSG (spécification § 8.2) : un appel par opération, requêtes signées. Erreurs réseau en
 * {@link IOException} (à réessayer), refus du relais en {@link RelayRefusedException}.
 */
public final class IuMsgClient {

    private static final String BASE = "/iu/v1/queues";

    private final HttpClient http;
    private final Clock clock;

    public IuMsgClient(HttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    /**
     * Identifiants d'une file.
     *
     * @param sendId identifiant de dépôt (publié)
     * @param recvId identifiant de relève (secret de la box)
     */
    public record Ids(String sendId, String recvId) {
    }

    /**
     * Message relevé.
     *
     * @param messageId  identifiant attribué par le relais (pour l'acquitter)
     * @param receivedAt dépôt sur le relais
     * @param envelope   enveloppe chiffrée
     */
    public record Received(String messageId, Instant receivedAt, byte[] envelope) {
    }

    /** Crée une file sur ce relais (requête de dépôt, signée par l'identité de la box). */
    public Ids createQueue(URI relay, UUID boxId, HybridKeyPair identity, PublicKey queueKey) throws IOException {
        byte[] body = Codec.json().writeValueAsBytes(java.util.Map.of("suite", "IU1", "queueKey",
                Codec.base64Url(queueKey.getEncoded())));
        SignedRequest signed = SignedRequest.of("POST", BASE, body, clock.instant());
        HttpRequest request = builder(relay, BASE, signed, boxId.toString(), signed.signDeposit(identity))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        JsonNode ids = Codec.json().readTree(send(request, 201));
        return new Ids(ids.get("sendId").asString(), ids.get("recvId").asString());
    }

    /** Dépose une enveloppe dans la file d'une autre box. */
    public String deposit(QueueAddress to, UUID boxId, HybridKeyPair identity, byte[] envelope) throws IOException {
        String path = BASE + "/" + to.sendId() + "/messages";
        SignedRequest signed = SignedRequest.of("POST", path, envelope, clock.instant());
        HttpRequest request = builder(to.relay(), path, signed, boxId.toString(), signed.signDeposit(identity))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(envelope)).build();
        return Codec.json().readTree(send(request, 202)).get("messageId").asString();
    }

    /** Relève les messages en attente, en attendant au plus {@code wait} s'il n'y en a aucun. */
    public List<Received> receive(URI relay, String recvId, PrivateKey queueKey, Duration wait) throws IOException {
        String path = BASE + "/" + recvId + "/messages";
        SignedRequest signed = SignedRequest.of("GET", path, null, clock.instant());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base(relay) + path + "?wait="
                + wait.toSeconds())).timeout(wait.plusSeconds(60));
        IuFileClientHeaders.apply(request, signed, recvId, signed.signCapability(queueKey));
        HttpResponse<byte[]> response = exchange(request.GET().build());
        if (response.statusCode() == 204) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw IuFileClient.refused(response.statusCode(), response.body());
        }
        return Codec.json().readTree(response.body()).valueStream()
                .map(m -> new Received(m.get("messageId").asString(), Instant.parse(m.get("receivedAt").asString()),
                        Codec.fromBase64Url(m.get("content").asString(), "Message")))
                .toList();
    }

    /** Acquitte (supprime) un message relevé. */
    public void ack(URI relay, String recvId, PrivateKey queueKey, String messageId) throws IOException {
        String path = BASE + "/" + recvId + "/messages/" + messageId;
        SignedRequest signed = SignedRequest.of("DELETE", path, null, clock.instant());
        send(builder(relay, path, signed, recvId, signed.signCapability(queueKey)).DELETE().build(), 204);
    }

    /** Renouvelle les identifiants de la file (à republier dans l'annuaire). */
    public Ids rotate(URI relay, String recvId, PrivateKey queueKey) throws IOException {
        String path = BASE + "/" + recvId + "/rotate";
        SignedRequest signed = SignedRequest.of("POST", path, null, clock.instant());
        JsonNode ids = Codec.json().readTree(send(builder(relay, path, signed, recvId,
                signed.signCapability(queueKey)).POST(HttpRequest.BodyPublishers.noBody()).build(), 200));
        return new Ids(ids.get("sendId").asString(), ids.get("recvId").asString());
    }

    private static String base(URI relay) {
        return relay.toString().replaceAll("/+$", "");
    }

    private static HttpRequest.Builder builder(URI relay, String path, SignedRequest signed, String keyId,
                                               String signature) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base(relay) + path))
                .timeout(Duration.ofMinutes(2));
        return IuFileClientHeaders.apply(builder, signed, keyId, signature);
    }

    private byte[] send(HttpRequest request, int expected) throws IOException {
        HttpResponse<byte[]> response = exchange(request);
        if (response.statusCode() != expected) {
            throw IuFileClient.refused(response.statusCode(), response.body());
        }
        return response.body();
    }

    private HttpResponse<byte[]> exchange(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Requête interrompue", e);
        }
    }
}
