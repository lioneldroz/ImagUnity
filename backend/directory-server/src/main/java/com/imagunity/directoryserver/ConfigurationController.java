package com.imagunity.directoryserver;

import com.imagunity.rules.RuleSet;
import com.imagunity.directory.CentralConfiguration;
import com.imagunity.directory.SignedDocument;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration centralisée.
 *
 * <pre>
 * GET  /directory/v1/configurations           documents publiés (lecture libre : signés, sans donnée de patient)
 * POST /directory/v1/configurations           dépôt d'un document signé (compte du responsable)
 * POST /directory/v1/configurations/reports   compte rendu d'une box (signé par la box)
 * GET  /admin/api/state                       interface : box, versions appliquées, documents publiés
 * POST /admin/api/draft                       interface : brouillon à signer (règles vérifiées)
 * POST /admin/api/trial                       interface : essai des règles sur un en-tête synthétique
 * POST /admin/api/publish                     interface : dépôt d'un document signé
 * </pre>
 */
@RestController
class ConfigurationController {

    /** Réponse de {@code GET /configurations}. */
    record DocumentsView(String organization, List<SignedDocument> configurations) {
    }

    /** Essai : règles du brouillon et en-tête. */
    record TrialRequest(ConfigurationService.DraftRequest draft, Map<String, String> header) {
    }

    private final ConfigurationService service;
    private final String organization;

    ConfigurationController(ConfigurationService service,
                            com.imagunity.directory.KeyFiles.OrganizationPublicKey organizationKey) {
        this.service = service;
        this.organization = organizationKey.organization();
    }

    @GetMapping("/directory/v1/configurations")
    DocumentsView configurations() {
        return new DocumentsView(organization, service.documents());
    }

    @PostMapping(path = "/directory/v1/configurations", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> publish(@RequestBody String body) {
        PublicationService.Outcome outcome = service.publish(read(body));
        return ResponseEntity.status(outcome == PublicationService.Outcome.PUBLISHED ? HttpStatus.CREATED
                : HttpStatus.OK).build();
    }

    /** Plus long compte rendu admis ; lu avec cette limite, avant toute vérification (l'appel est public). */
    static final int MAX_REPORT = 64 * 1024;

    @PostMapping(path = "/directory/v1/configurations/reports", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> report(jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_REPORT + 1);
        if (body.length > MAX_REPORT) {
            throw new PublicationService.InvalidEntryException("Compte rendu trop long");
        }
        return ResponseEntity.status(service.report(read(new String(body, java.nio.charset.StandardCharsets.UTF_8)))
                ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @GetMapping("/admin/api/state")
    ConfigurationService.State state() {
        return service.state();
    }

    @PostMapping(path = "/admin/api/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    CentralConfiguration draft(@RequestBody ConfigurationService.DraftRequest request) {
        return service.draft(request);
    }

    @PostMapping(path = "/admin/api/trial", consumes = MediaType.APPLICATION_JSON_VALUE)
    RuleSet.Trial trial(@RequestBody TrialRequest request) {
        return service.trial(request.draft(), request.header() == null ? Map.of() : request.header());
    }

    @PostMapping(path = "/admin/api/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> publishFromInterface(@RequestBody String body) {
        return publish(body);
    }

    private static SignedDocument read(String body) {
        try {
            return SignedDocument.fromJson(body);
        } catch (RuntimeException e) {
            throw new PublicationService.InvalidEntryException("Document signé illisible : " + e.getMessage());
        }
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
