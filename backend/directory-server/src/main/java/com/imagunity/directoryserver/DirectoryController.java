package com.imagunity.directoryserver;

import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.SignedDocument;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de l'annuaire.
 *
 * <pre>
 * GET  /directory/v1/organization            organisation et clé publique du responsable (information ; les box
 *                                            épinglent la leur)
 * GET  /directory/v1/entries                 entrée courante de chaque box
 * GET  /directory/v1/entries/{boxId}         entrée courante d'une box (404 si inconnue)
 * GET  /directory/v1/entries/{boxId}/history toutes ses entrées, par numéro croissant
 * POST /directory/v1/entries                 dépôt par le responsable : 201 publiée, 200 déjà publiée,
 *                                            409 retour en arrière ou conflit, 422 entrée invalide
 * </pre>
 */
@RestController
@RequestMapping("/directory/v1")
class DirectoryController {

    /** Réponse de {@code GET /organization}. */
    record OrganizationView(String organization, HybridPublicKey.Encoded signingKeys, String shortCode) {
    }

    /** Réponse de {@code GET /entries}. */
    record EntriesView(String organization, List<SignedDocument> entries) {
    }

    private final EntryStore store;
    private final PublicationService publication;
    private final KeyFiles.OrganizationPublicKey organizationKey;

    DirectoryController(EntryStore store, PublicationService publication,
                        KeyFiles.OrganizationPublicKey organizationKey) {
        this.store = store;
        this.publication = publication;
        this.organizationKey = organizationKey;
    }

    @GetMapping("/organization")
    OrganizationView organization() {
        return new OrganizationView(organizationKey.organization(), organizationKey.signingKeys(),
                organizationKey.key().shortCode());
    }

    @GetMapping("/entries")
    EntriesView entries() {
        return new EntriesView(organizationKey.organization(), store.current());
    }

    @GetMapping("/entries/{boxId}")
    SignedDocument entry(@PathVariable UUID boxId) {
        return store.current(boxId).orElseThrow(() -> new NoSuchElementException("Box inconnue : " + boxId));
    }

    @GetMapping("/entries/{boxId}/history")
    List<SignedDocument> history(@PathVariable UUID boxId) {
        List<SignedDocument> history = store.history(boxId);
        if (history.isEmpty()) {
            throw new NoSuchElementException("Box inconnue : " + boxId);
        }
        return history;
    }

    @PostMapping("/entries")
    ResponseEntity<Void> publish(@RequestBody String body) {
        SignedDocument document;
        try {
            document = SignedDocument.fromJson(body);
        } catch (RuntimeException e) {
            throw new PublicationService.InvalidEntryException("Document signé illisible : " + e.getMessage());
        }
        PublicationService.Outcome outcome = publication.publish(document);
        return ResponseEntity.status(outcome == PublicationService.Outcome.PUBLISHED ? HttpStatus.CREATED
                : HttpStatus.OK).build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PublicationService.InvalidEntryException.class)
    ProblemDetail invalid(PublicationService.InvalidEntryException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(PublicationService.ConflictException.class)
    ProblemDetail conflict(PublicationService.ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
