package com.imagunity.api;

import com.imagunity.dicom.StowService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Réception DICOMweb STOW-RS : le PACS dépose des instances par HTTP, comme il les enverrait par C-STORE.
 *
 * <pre>
 * POST /dicomweb/{AET}/studies           instances (multipart/related; type="application/dicom")
 * POST /dicomweb/{AET}/studies/{study}   instances de cette étude seulement
 * </pre>
 * {@code AET} est la destination DICOM : une route fixe ({@code IU_TO_B}) ou la destination de routage
 * ({@code IU_AUTO}). Accès réservé au compte DICOMweb ; le corps est lu en flux.
 */
@RestController
@RequestMapping("/dicomweb/{aet}/studies")
public class DicomWebController {

    private final ObjectProvider<StowService> stow;

    public DicomWebController(ObjectProvider<StowService> stow) {
        this.stow = stow;
    }

    @PostMapping
    public ResponseEntity<String> store(@PathVariable String aet, HttpServletRequest request, Principal principal)
            throws IOException {
        return handle(aet, null, request, principal);
    }

    @PostMapping("/{study}")
    public ResponseEntity<String> storeStudy(@PathVariable String aet, @PathVariable String study,
                                             HttpServletRequest request, Principal principal) throws IOException {
        return handle(aet, study, request, principal);
    }

    private ResponseEntity<String> handle(String aet, String study, HttpServletRequest request, Principal principal)
            throws IOException {
        StowService service = stow.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(404).header(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8")
                    .body("Entrée DICOM désactivée sur cette box");
        }
        String source = "dicomweb:" + (principal == null ? "anonyme" : principal.getName());
        StowService.Response response = service.store(source, aet, study, request.getContentType(),
                request.getInputStream());
        return ResponseEntity.status(response.status()).header(HttpHeaders.CONTENT_TYPE, response.contentType())
                .body(response.body());
    }
}
