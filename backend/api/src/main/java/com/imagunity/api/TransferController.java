package com.imagunity.api;

import com.imagunity.domain.inbound.InboundRepository;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.domain.transfer.TransferState;
import com.imagunity.orchestration.RetryRefusedException;
import com.imagunity.orchestration.SendOrchestrator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Suivi des transferts pour la console.
 *
 * <pre>
 * GET  /api/v1/overview                         indicateurs (transferts par état, accusés en retard, partenaires)
 * GET  /api/v1/transfers?limit=50               transferts envoyés, du plus récent au plus ancien
 * GET  /api/v1/transfers/{id}                   transfert envoyé et ses paquets
 * POST /api/v1/transfers/{id}/retry             relance d'un transfert en échec (404, 409)
 * GET  /api/v1/inbound-transfers?limit=50       transferts reçus
 * GET  /api/v1/events                           flux SSE : événements « transfer » et « inbound »
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class TransferController {

    static final int MAX_LIMIT = 500;
    static final Duration RETRY_TIMEOUT = Duration.ofSeconds(30);

    private final TransferRepository transfers;
    private final InboundRepository inbound;
    private final PeerRepository peers;
    private final SendOrchestrator sender;
    private final ConsoleEvents events;
    private final ConsoleProperties properties;
    private final Clock clock;

    public TransferController(TransferRepository transfers, InboundRepository inbound, PeerRepository peers,
                              SendOrchestrator sender, ConsoleEvents events, ConsoleProperties properties,
                              Clock clock) {
        this.transfers = transfers;
        this.inbound = inbound;
        this.peers = peers;
        this.sender = sender;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/overview")
    public TransferViews.Overview overview() {
        Instant now = clock.instant();
        long overdue = transfers.findByStates(Set.of(TransferState.ANNOUNCED)).stream()
                .filter(t -> view(t, now).ackOverdue()).count();
        return new TransferViews.Overview(transfers.countByState(), inbound.countTransfersByState(), overdue,
                peers.findAll().size());
    }

    @GetMapping("/transfers")
    public List<TransferViews.Outbound> transfers(@RequestParam(defaultValue = "50") int limit) {
        Instant now = clock.instant();
        return transfers.findRecent(checkLimit(limit)).stream().map(t -> view(t, now)).toList();
    }

    @GetMapping("/transfers/{id}")
    public TransferViews.OutboundDetail transfer(@PathVariable UUID id) {
        Transfer transfer = transfers.find(id).orElseThrow(() -> new NoSuchElementException("Transfert inconnu : " + id));
        return new TransferViews.OutboundDetail(view(transfer, clock.instant()),
                transfers.parts(id).stream().map(TransferViews.Part::of).toList());
    }

    @PostMapping("/transfers/{id}/retry")
    public TransferViews.Outbound retry(@PathVariable UUID id) throws InterruptedException, TimeoutException {
        try {
            Transfer retried = sender.retry(id).get(RETRY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return view(retried, clock.instant());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    @GetMapping("/inbound-transfers")
    public List<TransferViews.Inbound> inboundTransfers(@RequestParam(defaultValue = "50") int limit) {
        return inbound.findRecentTransfers(checkLimit(limit)).stream().map(TransferViews.Inbound::of).toList();
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return events.subscribe();
    }

    private TransferViews.Outbound view(Transfer transfer, Instant now) {
        return TransferViews.Outbound.of(transfer, now, properties.ackOverdue());
    }

    private static int checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit doit être compris entre 1 et " + MAX_LIMIT);
        }
        return limit;
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RetryRefusedException.class)
    ProblemDetail retryRefused(RetryRefusedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(TimeoutException.class)
    ProblemDetail busy(TimeoutException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "L'orchestrateur n'a pas répondu à temps ; la relance aura lieu dès qu'il sera libre");
    }
}
