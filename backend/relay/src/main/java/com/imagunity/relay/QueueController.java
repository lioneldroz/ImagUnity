package com.imagunity.relay;

import com.imagunity.protocol.Codec;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * IU-MSG (spécification § 8.2).
 *
 * <pre>
 * POST   /iu/v1/queues                              créer une file       dépôt (box enrôlée et active)
 * POST   /iu/v1/queues/{sendId}/messages            déposer un message   dépôt (box enrôlée et active)
 * GET    /iu/v1/queues/{recvId}/messages?wait=30    relever              capacité de la file
 * DELETE /iu/v1/queues/{recvId}/messages/{id}       acquitter            capacité de la file
 * POST   /iu/v1/queues/{recvId}/rotate              renouveler           capacité de la file
 * </pre>
 */
@RestController
@RequestMapping("/iu/v1/queues")
public class QueueController {

    private static final String EMPTY = Codec.sha256Hex(new byte[0]);
    /** Taille maximale d'une demande de création de file (suite, clé publique, identifiant d'envoi réservé). */
    static final int MAX_CREATE = 16 * 1024;

    private final QueueService queues;
    private final RequestAuthenticator auth;

    public QueueController(QueueService queues, RequestAuthenticator auth) {
        this.queues = queues;
        this.auth = auth;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QueueService.Ids> create(HttpServletRequest request) throws IOException {
        // Lu avec une limite, avant toute authentification : un corps énorme n'épuise pas la mémoire du relais.
        byte[] body = request.getInputStream().readNBytes(MAX_CREATE + 1);
        if (body.length > MAX_CREATE) {
            throw new RelayException(HttpStatus.CONTENT_TOO_LARGE, "Demande de création trop longue");
        }
        UUID box = auth.depositor(request, Codec.sha256Hex(body));
        QueueService.CreateRequest create;
        try {
            create = Codec.json().readValue(body, QueueService.CreateRequest.class);
        } catch (RuntimeException e) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Demande de création illisible");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(queues.create(box, create));
    }

    @PostMapping("/{sendId}/messages")
    public ResponseEntity<QueueService.Deposited> deposit(@PathVariable String sendId, HttpServletRequest request)
            throws IOException {
        QueueStore.Queue queue = queues.bySendId(sendId);
        byte[] envelope = request.getInputStream().readNBytes(QueueService.MAX_ENVELOPE + 1);
        UUID box = auth.depositor(request, Codec.sha256Hex(envelope));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(queues.deposit(queue, box, envelope));
    }

    @GetMapping("/{recvId}/messages")
    public ResponseEntity<List<QueueService.Delivered>> receive(@PathVariable String recvId,
                                                                @RequestParam(defaultValue = "0") int wait,
                                                                HttpServletRequest request)
            throws InterruptedException {
        QueueStore.Queue queue = queues.byRecvId(recvId);
        auth.capability(request, EMPTY, recvId, queue.queueKey());
        List<QueueService.Delivered> messages = queues.receive(queue, Duration.ofSeconds(Math.max(0, wait)));
        return messages.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(messages);
    }

    @DeleteMapping("/{recvId}/messages/{messageId}")
    public ResponseEntity<Void> ack(@PathVariable String recvId, @PathVariable String messageId,
                                    HttpServletRequest request) {
        QueueStore.Queue queue = queues.byRecvId(recvId);
        auth.capability(request, EMPTY, recvId, queue.queueKey());
        queues.ack(queue, messageId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{recvId}/rotate")
    public ResponseEntity<QueueService.Ids> rotate(@PathVariable String recvId, HttpServletRequest request) {
        QueueStore.Queue queue = queues.byRecvId(recvId);
        auth.capability(request, EMPTY, recvId, queue.queueKey());
        return ResponseEntity.ok(queues.rotate(queue));
    }

    @ExceptionHandler(RelayException.class)
    ResponseEntity<ProblemDetail> refused(RelayException e) {
        return ResponseEntity.status(e.status()).body(ProblemDetail.forStatusAndDetail(e.status(), e.getMessage()));
    }
}
