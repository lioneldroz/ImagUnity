package com.imagunity.api;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.orchestration.BoxIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Annuaire signé, vu de la box.
 *
 * <pre>
 * GET /api/v1/directory                       identité de la box, synchronisation, box connues (enabled:false sans annuaire)
 * GET /api/v1/directory/enrollment-request    demande d'enrôlement signée, à remettre au responsable (404 sans annuaire)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryController {

    /** Une box de l'annuaire. */
    public record BoxView(UUID boxId, String displayName, long serial, BoxEntry.Status status, Instant validUntil) {
    }

    /** Dernière synchronisation. */
    public record SyncView(Instant at, int accepted, int rejected, String error) {
    }

    /** Réponse de {@code GET /api/v1/directory}. */
    public record DirectoryView(boolean enabled, UUID boxId, String shortCode, SyncView lastSync, List<BoxView> boxes) {
    }

    private final ObjectProvider<BoxIdentity> identity;
    private final ObjectProvider<LocalDirectory> directory;
    private final BoxProperties box;
    private final Clock clock;

    public DirectoryController(ObjectProvider<BoxIdentity> identity, ObjectProvider<LocalDirectory> directory,
                               BoxProperties box, Clock clock) {
        this.identity = identity;
        this.directory = directory;
        this.box = box;
        this.clock = clock;
    }

    @GetMapping
    public DirectoryView directory() {
        BoxIdentity me = identity.getIfAvailable();
        LocalDirectory local = directory.getIfAvailable();
        if (me == null || local == null) {
            return new DirectoryView(false, null, null, null, List.of());
        }
        LocalDirectory.SyncStatus sync = local.lastSync();
        Instant now = clock.instant();
        List<BoxView> boxes = local.entries().stream()
                .sorted(Comparator.comparing(BoxEntry::displayName))
                .map(e -> new BoxView(e.boxId(), e.displayName(), e.serial(), e.statusAt(now), e.validUntil()))
                .toList();
        return new DirectoryView(true, me.boxId(), me.keys().publicKey().shortCode(),
                new SyncView(sync.at(), sync.accepted(), sync.rejected(), sync.error()), boxes);
    }

    /** @param name nom proposé pour la box (par défaut, le nom de la box) */
    @GetMapping(path = "/enrollment-request", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> enrollmentRequest(@RequestParam(required = false) String name) {
        BoxIdentity me = identity.getIfAvailable();
        if (me == null) {
            throw new NoSuchElementException("Annuaire non activé sur cette box");
        }
        String displayName = name == null || name.isBlank() ? box.name() : name;
        String request = EnrollmentRequest.create(me.keys(), me.boxId(), displayName, Map.of(), clock.instant())
                .toJson();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"demande-" + me.boxId() + ".json\"")
                .body(request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
