package com.imagunity.directoryserver;

import com.imagunity.directory.SignedDocument;
import java.util.List;
import java.util.NoSuchElementException;
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
 * API de la fédération.
 *
 * <pre>
 * GET  /directory/v1/recognitions                 reconnaissance courante de chaque partenaire
 * POST /directory/v1/recognitions                 dépôt par le responsable : 201 publiée, 200 déjà publiée,
 *                                                 409 retour en arrière ou conflit, 422 invalide
 * GET  /directory/v1/partners/{empreinte}/entries entrées relayées d'un partenaire dont l'accord est actif (404 sinon)
 * </pre>
 */
@RestController
@RequestMapping("/directory/v1")
class FederationController {

    /** Réponse de {@code GET /recognitions}. */
    record RecognitionsView(String organization, List<SignedDocument> recognitions) {
    }

    /** Réponse de {@code GET /partners/{empreinte}/entries}. */
    record EntriesView(String organization, List<SignedDocument> entries) {
    }

    private final FederationService federation;

    FederationController(FederationService federation) {
        this.federation = federation;
    }

    @GetMapping("/recognitions")
    RecognitionsView recognitions() {
        return new RecognitionsView(federation.organization(), federation.current());
    }

    @PostMapping(path = "/recognitions", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> publish(@RequestBody String body) {
        SignedDocument document;
        try {
            document = SignedDocument.fromJson(body);
        } catch (RuntimeException e) {
            throw new PublicationService.InvalidEntryException("Document signé illisible : " + e.getMessage());
        }
        return ResponseEntity.status(federation.publish(document) == PublicationService.Outcome.PUBLISHED
                ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @GetMapping("/partners/{partner}/entries")
    EntriesView partnerEntries(@PathVariable String partner) {
        if (!partner.matches("[0-9a-f]{64}")) {
            throw new NoSuchElementException("Empreinte de partenaire mal formée");
        }
        FederationService.PartnerEntries entries = federation.partnerEntries(partner);
        return new EntriesView(entries.organization(), entries.entries());
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
