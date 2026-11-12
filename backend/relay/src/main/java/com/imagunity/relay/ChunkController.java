package com.imagunity.relay;

import com.imagunity.protocol.Codec;
import com.imagunity.protocol.ProtocolException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * IU-FILE (spécification § 6.2).
 *
 * <pre>
 * POST   /iu/v1/chunks                   créer un morceau            dépôt (box enrôlée et active)
 * PUT    /iu/v1/chunks/{senderId}        téléverser                  capacité émetteur
 * GET    /iu/v1/chunks/{recipientId}     télécharger (Range, wait)   capacité retrait
 * POST   /iu/v1/chunks/{recipientId}/ack acquitter                   capacité retrait
 * DELETE /iu/v1/chunks/{senderId}        supprimer                   capacité émetteur
 * GET    /iu/v1/chunks/{senderId}        état                        capacité émetteur
 * </pre>
 * Les identifiants d'émetteur et de retrait sont aléatoires et distincts : un même chemin {@code GET} sert à l'état
 * (identifiant d'émetteur) et au téléchargement (identifiant de retrait).
 */
@RestController
@RequestMapping("/iu/v1/chunks")
public class ChunkController {

    private static final String EMPTY = Codec.sha256Hex(new byte[0]);

    private final ChunkService chunks;
    private final ChunkFiles files;
    private final RequestAuthenticator auth;

    public ChunkController(ChunkService chunks, ChunkFiles files, RequestAuthenticator auth) {
        this.chunks = chunks;
        this.files = files;
        this.auth = auth;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChunkService.Created> create(@RequestBody byte[] body, HttpServletRequest request)
            throws IOException {
        UUID box = auth.depositor(request, Codec.sha256Hex(body));
        ChunkService.CreateRequest create;
        try {
            create = Codec.json().readValue(body, ChunkService.CreateRequest.class);
        } catch (RuntimeException e) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Demande de création illisible");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(chunks.create(box, create));
    }

    @PutMapping("/{senderId}")
    public ResponseEntity<Void> upload(@PathVariable String senderId, HttpServletRequest request) throws IOException {
        ChunkStore.Chunk chunk = chunks.chunk(senderId);
        ChunkFiles.Received received = files.receive(request.getInputStream(), chunk.size());
        try {
            auth.capability(request, received.sha256(), senderId, chunk.senderKey());
        } catch (RelayException e) {
            java.nio.file.Files.deleteIfExists(received.temp());
            throw e;
        }
        chunks.upload(chunk, received);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id, @RequestParam(defaultValue = "0") int wait,
                                 HttpServletRequest request) throws InterruptedException {
        java.util.Optional<ChunkStore.Recipient> recipient = recipientIfAny(id);
        if (recipient.isEmpty()) {
            ChunkStore.Chunk chunk = chunks.chunk(id);
            auth.capability(request, EMPTY, id, chunk.senderKey());
            return ResponseEntity.ok(chunks.state(chunk));
        }
        auth.capability(request, EMPTY, id, recipient.get().recipientKey());
        Resource content = new FileSystemResource(chunks.download(recipient.get(),
                Duration.ofSeconds(Math.max(0, wait))));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(content);
    }

    @PostMapping("/{recipientId}/ack")
    public ResponseEntity<Void> ack(@PathVariable String recipientId, HttpServletRequest request) throws IOException {
        ChunkStore.Recipient recipient = chunks.recipient(recipientId);
        auth.capability(request, EMPTY, recipientId, recipient.recipientKey());
        chunks.ack(recipient);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{senderId}")
    public ResponseEntity<Void> delete(@PathVariable String senderId, HttpServletRequest request) throws IOException {
        ChunkStore.Chunk chunk = chunks.chunk(senderId);
        auth.capability(request, EMPTY, senderId, chunk.senderKey());
        chunks.delete(senderId);
        return ResponseEntity.noContent().build();
    }

    private java.util.Optional<ChunkStore.Recipient> recipientIfAny(String id) {
        try {
            return java.util.Optional.of(chunks.recipient(id));
        } catch (RelayException e) {
            return java.util.Optional.empty();
        }
    }

    @ExceptionHandler(RelayException.class)
    ResponseEntity<ProblemDetail> refused(RelayException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        if (e.status() == HttpStatus.TOO_EARLY) {
            response.header(HttpHeaders.RETRY_AFTER, "2");
        }
        return response.body(ProblemDetail.forStatusAndDetail(e.status(), e.getMessage()));
    }

    @ExceptionHandler(ProtocolException.class)
    ResponseEntity<ProblemDetail> malformed(ProtocolException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e.getMessage()));
    }
}
