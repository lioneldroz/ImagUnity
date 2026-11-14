package com.imagunity.directoryserver;

import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demandes d'enrôlement.
 *
 * <pre>
 * POST /directory/v1/enrollment-requests   dépôt par une box (sans compte) : 202 enregistrée, 409 box déjà active,
 *                                          422 demande invalide, 429 trop de demandes en attente
 * GET  /directory/v1/enrollment-requests   demandes en attente (compte du responsable)
 * </pre>
 */
@RestController
class EnrollmentController {

    private final EnrollmentService enrollments;

    EnrollmentController(EnrollmentService enrollments) {
        this.enrollments = enrollments;
    }

    @PostMapping(path = "/directory/v1/enrollment-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> submit(jakarta.servlet.http.HttpServletRequest request) throws IOException {
        // Lu avec une limite, avant tout traitement : ce dépôt est ouvert à toutes les box, sans compte.
        byte[] body = request.getInputStream().readNBytes(com.imagunity.directory.EnrollmentSubmission.MAX_SIZE + 1);
        if (body.length > com.imagunity.directory.EnrollmentSubmission.MAX_SIZE) {
            throw new PublicationService.InvalidEntryException("Demande d'enrôlement trop longue");
        }
        enrollments.submit(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/directory/v1/enrollment-requests")
    List<EnrollmentService.Pending> pending() {
        return enrollments.pending();
    }

    @ExceptionHandler(PublicationService.InvalidEntryException.class)
    ProblemDetail invalid(PublicationService.InvalidEntryException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(PublicationService.ConflictException.class)
    ProblemDetail conflict(PublicationService.ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(EnrollmentService.TooManyException.class)
    ProblemDetail tooMany(EnrollmentService.TooManyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }
}
