package com.imagunity.directoryserver;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import com.imagunity.directory.TestRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Demandes d'essai de connexion (« Tester maintenant ») et flux d'événements des box.
 *
 * <pre>
 * POST /directory/v1/test-requests           dépôt d'une demande signée par le responsable (compte du responsable)
 * GET  /directory/v1/test-requests/{boxId}   dernière demande de cette box (la box la vérifie)
 * GET  /directory/v1/events                  flux de réveil des box (Server-Sent Events)
 * </pre>
 */
@RestController
class TestRequestController {

    /** Plus longue demande admise. */
    static final int MAX_REQUEST = 64 * 1024;

    private final TestRequestStore store;
    private final EntryStore entries;
    private final DirectoryEvents events;
    private final KeyFiles.OrganizationPublicKey key;
    private final Clock clock;

    TestRequestController(TestRequestStore store, EntryStore entries, DirectoryEvents events,
                          KeyFiles.OrganizationPublicKey key, Clock clock) {
        this.store = store;
        this.entries = entries;
        this.events = events;
        this.key = key;
        this.clock = clock;
    }

    @PostMapping(path = "/directory/v1/test-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> deposit(jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST + 1);
        if (body.length > MAX_REQUEST) {
            throw new PublicationService.InvalidEntryException("Demande d'essai trop longue");
        }
        SignedDocument document;
        TestRequest test;
        try {
            document = SignedDocument.fromJson(new String(body, StandardCharsets.UTF_8));
            test = TestRequest.verify(document, key.organization(), key.key(), clock.instant());
        } catch (DirectoryException | IllegalArgumentException e) {
            throw new PublicationService.InvalidEntryException("Demande d'essai refusée : " + e.getMessage());
        }
        boolean active = entries.current(test.boxId()).map(d -> {
            try {
                return new EntryVerifier(key.organization(), key.key(), SerialMemory.inMemory(), clock).verify(d)
                        .entry().statusAt(clock.instant()) == BoxEntry.Status.ACTIVE;
            } catch (RuntimeException e) {
                return false;
            }
        }).orElse(false);
        if (!active) {
            throw new PublicationService.InvalidEntryException("Box " + test.boxId()
                    + " absente de l'annuaire ou inactive");
        }
        store.save(test.boxId(), document, test.requestedAt(), clock.instant());
        events.testRequested(test.boxId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping(path = "/directory/v1/test-requests/{boxId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> find(@PathVariable UUID boxId) {
        return store.find(boxId).map(d -> ResponseEntity.ok(d.toJson())).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(path = "/directory/v1/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        return events.subscribe();
    }

    @ExceptionHandler(PublicationService.InvalidEntryException.class)
    ProblemDetail invalid(PublicationService.InvalidEntryException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(DirectoryEvents.TooManyListeners.class)
    ProblemDetail busy(DirectoryEvents.TooManyListeners e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
