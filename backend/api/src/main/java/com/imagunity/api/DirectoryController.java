package com.imagunity.api;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.orchestration.PairingService;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Annuaire signé, vu de la box.
 *
 * <pre>
 * GET  /api/v1/directory                      identité de la box, synchronisation, box connues (enabled:false sans annuaire)
 * GET  /api/v1/directory/enrollment-request   demande d'enrôlement signée, avec l'adresse de contact de la box
 * POST /api/v1/directory/boxes/{boxId}/pair   {"name": "box-b"} : se connecte à l'adresse publiée de cette box (202)
 * </pre>
 * L'appairage par l'annuaire n'est qu'un début : le partenaire n'est autorisé qu'une fois lié à cette box par la
 * poignée de main signée.
 */
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryController {


    /** Une box de l'annuaire. */
    /**
     * @param reachable vrai si la box publie une adresse de contact
     * @param pairedAs  nom local du partenaire lié à cette box ({@code null} : pas appairée)
     * @param encrypted vrai si l'entrée publie les clés de chiffrement de la box (sinon, entrée à réémettre)
     */
    public record BoxView(UUID boxId, String displayName, long serial, BoxEntry.Status status, Instant validUntil,
                          boolean reachable, String pairedAs, boolean encrypted) {
    }

    /** Corps de {@code POST /boxes/{boxId}/pair}. */
    public record PairRequest(String name) {
    }

    /** Dernière synchronisation. */
    public record SyncView(Instant at, int accepted, int rejected, String error) {
    }

    /** Réponse de {@code GET /api/v1/directory}. */
    public record DirectoryView(boolean enabled, UUID boxId, String shortCode, SyncView lastSync, List<BoxView> boxes) {
    }

    private static final System.Logger LOG = System.getLogger(DirectoryController.class.getName());

    private final ObjectProvider<BoxIdentity> identity;
    private final ObjectProvider<LocalDirectory> directory;
    private final BoxProperties box;
    private final Clock clock;
    private final TransportPort transport;
    private final PairingService pairing;
    private final PeerRepository peers;

    public DirectoryController(ObjectProvider<BoxIdentity> identity, ObjectProvider<LocalDirectory> directory,
                               BoxProperties box, Clock clock, TransportPort transport, PairingService pairing,
                               PeerRepository peers) {
        this.identity = identity;
        this.directory = directory;
        this.box = box;
        this.clock = clock;
        this.transport = transport;
        this.pairing = pairing;
        this.peers = peers;
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
        Map<UUID, String> paired = peers.findAll().stream().filter(p -> p.boxId() != null)
                .collect(Collectors.toMap(Peer::boxId, Peer::name, (a, b) -> a));
        List<BoxView> boxes = local.entries().stream()
                .sorted(Comparator.comparing(BoxEntry::displayName))
                .map(e -> new BoxView(e.boxId(), e.displayName(), e.serial(), e.statusAt(now), e.validUntil(),
                        e.transport().containsKey(transport.addressKind()), paired.get(e.boxId()), e.encryptionKeys() != null))
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
        Map<String, String> addresses = new HashMap<>();
        try {
            addresses.putAll(transport.contactAddresses());
        } catch (TransportException e) {
            LOG.log(System.Logger.Level.WARNING, "Demande d''enrôlement sans adresse de contact : {0}", e.getMessage());
        }
        String request = EnrollmentRequest.create(me.keys(), me.encryption().publicKey(), me.boxId(), displayName,
                addresses, clock.instant()).toJson();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"demande-" + me.boxId() + ".json\"")
                .body(request);
    }

    /** Se connecte à l'adresse publiée d'une box active de l'organisation, sous le nom choisi par l'opérateur. */
    @PostMapping("/boxes/{boxId}/pair")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void pair(@PathVariable UUID boxId, @RequestBody PairRequest request) {
        BoxIdentity me = identity.getIfAvailable();
        LocalDirectory local = directory.getIfAvailable();
        if (me == null || local == null) {
            throw new NoSuchElementException("Annuaire non activé sur cette box");
        }
        BoxEntry entry = local.entry(boxId).orElseThrow(() -> new NoSuchElementException("Box inconnue : " + boxId));
        if (boxId.equals(me.boxId())) {
            throw new IllegalStateException("C'est cette box");
        }
        if (entry.statusAt(clock.instant()) != BoxEntry.Status.ACTIVE) {
            throw new IllegalStateException("Box « " + entry.displayName() + " » non active dans l'annuaire");
        }
        String address = entry.transport().get(transport.addressKind());
        if (address == null) {
            throw new IllegalStateException("Box « " + entry.displayName() + " » sans adresse de contact publiée");
        }
        if (peers.findAll().stream().anyMatch(p -> boxId.equals(p.boxId()))) {
            throw new IllegalStateException("Box « " + entry.displayName() + " » déjà appairée");
        }
        pairing.join(request.name(), address, boxId);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(TransportException.class)
    ProblemDetail transportUnavailable(TransportException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
