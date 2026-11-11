package com.imagunity.api;

import com.imagunity.domain.peer.Peer;
import com.imagunity.orchestration.PairingService;
import com.imagunity.transport.Invitation;
import com.imagunity.transport.TransportException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Appairage des partenaires (preuve de concept, sans annuaire).
 *
 * <pre>
 * POST /api/v1/peers/invitations  {"name": "box-b"}                 → {"name", "link"}
 * POST /api/v1/peers/connections  {"name": "box-a", "link": "..."}  → 202
 * GET  /api/v1/peers                                                → [{"name", "displayName", "pairedAt"}]
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/peers")
public class PeerController {

    public record InvitationRequest(String name) {
    }

    public record InvitationResponse(String name, String link) {
    }

    public record JoinRequest(String name, String link) {
    }

    public record PeerView(String name, String displayName, Instant pairedAt) {
    }

    private final PairingService pairing;

    public PeerController(PairingService pairing) {
        this.pairing = pairing;
    }

    /** Crée un lien d'invitation, à transmettre hors bande au partenaire. */
    @PostMapping("/invitations")
    public InvitationResponse invite(@RequestBody InvitationRequest request) {
        Invitation invitation = pairing.invite(request.name());
        return new InvitationResponse(request.name(), invitation.link());
    }

    /** Rejoint le partenaire qui a émis le lien ; l'appairage se termine de façon asynchrone. */
    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void join(@RequestBody JoinRequest request) {
        if (request.link() == null || request.link().isBlank()) {
            throw new IllegalArgumentException("Lien d'invitation manquant");
        }
        pairing.join(request.name(), request.link());
    }

    @GetMapping
    public List<PeerView> peers() {
        return pairing.peers().stream().map(PeerController::view).toList();
    }

    private static PeerView view(Peer peer) {
        return new PeerView(peer.name(), peer.displayName(), peer.pairedAt());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(TransportException.class)
    ProblemDetail transportUnavailable(TransportException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
