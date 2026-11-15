package com.imagunity.api;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.SignedDocument;
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
 * GET  /api/v1/directory                      identité de la box, synchronisation, box connues, organisations
 *                                             partenaires (enabled:false sans annuaire)
 * GET  /api/v1/directory/enrollment-request   demande d'enrôlement signée, avec l'adresse de contact de la box
 * POST /api/v1/directory/boxes/{boxId}/pair   {"name": "box-b"} : se connecte à l'adresse publiée de cette box (202)
 * </pre>
 * L'appairage par l'annuaire n'est qu'un début : le partenaire n'est autorisé qu'une fois lié à cette box par la
 * poignée de main signée.
 */
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryController {


    /**
     * Une box de l'annuaire.
     *
     * @param reachable    vrai si la box publie une adresse de contact
     * @param pairedAs     nom local du partenaire lié à cette box ({@code null} : pas appairée)
     * @param encrypted    vrai si l'entrée publie les clés de chiffrement de la box (sinon, entrée à réémettre)
     * @param organization organisation partenaire de la box ({@code null} : box de l'organisation)
     * @param refusal      motif pour lequel cette box ne peut pas échanger avec elle (accord, périmètre, versions du
     *                     protocole), ou {@code null}
     * @param protocols    versions du protocole annoncées par la box ({@code iu1.0} si son entrée n'en annonce pas)
     * @param protocol     plus haute version commune avec cette box, ou {@code null}
     */
    public record BoxView(UUID boxId, String displayName, long serial, BoxEntry.Status status, Instant validUntil,
                          boolean reachable, String pairedAs, boolean encrypted, String organization,
                          String refusal, List<String> protocols, String protocol) {
    }

    /**
     * Une organisation partenaire reconnue par le responsable (fédération).
     *
     * @param boxes box de ce partenaire utilisables (accord actif, périmètre, relais)
     */
    public record PartnerView(String organization, String shortCode, BoxEntry.Status status, Instant validUntil,
                              List<String> localGroups, List<String> partnerGroups, List<String> relays,
                              SyncView lastSync, int boxes, FreshnessView freshness) {
    }

    /**
     * Fraîcheur d'un annuaire.
     *
     * @param issuedAt date de la dernière preuve retenue ({@code null} : aucune)
     * @param stale    vrai si elle est trop ancienne : échanges arrêtés avec cette organisation
     * @param error    motif de la dernière lecture écartée ou impossible, ou {@code null}
     */
    public record FreshnessView(Instant issuedAt, boolean stale, String error) {
    }

    /** Identifiant de box revendiqué par deux organisations : l'entrée de {@code claimant} a été écartée. */
    public record CollisionView(UUID boxId, String claimant, String holder, Instant at) {
    }

    /** Corps de {@code POST /boxes/{boxId}/pair}. */
    public record PairRequest(String name) {
    }

    /** Dernière synchronisation. */
    public record SyncView(Instant at, int accepted, int rejected, String error) {
    }

    /** Réponse de {@code GET /api/v1/directory}. */
    public record DirectoryView(boolean enabled, UUID boxId, String shortCode, SyncView lastSync, List<BoxView> boxes,
                                List<PartnerView> partners, List<CollisionView> collisions, FreshnessView freshness) {
    }

    private static FreshnessView freshness(LocalDirectory local, String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        LocalDirectory.SyncStatus sync = local.freshnessSync(fingerprint);
        return new FreshnessView(local.freshness(fingerprint).map(com.imagunity.directory.FreshnessProof::issuedAt)
                .orElse(null), local.stale(fingerprint), sync == null ? null : sync.error());
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
            return new DirectoryView(false, null, null, null, List.of(), List.of(), List.of(), null);
        }
        LocalDirectory.SyncStatus sync = local.lastSync();
        Instant now = clock.instant();
        Map<UUID, String> paired = peers.findAll().stream().filter(p -> p.boxId() != null)
                .collect(Collectors.toMap(Peer::boxId, Peer::name, (a, b) -> a));
        List<BoxView> boxes = local.entries().stream()
                .sorted(Comparator.comparing((BoxEntry e) -> !local.isLocal(e.boxId()))
                        .thenComparing(BoxEntry::displayName))
                .map(e -> new BoxView(e.boxId(), e.displayName(), e.serial(), e.statusAt(now), e.validUntil(),
                        e.transport().containsKey(transport.addressKind()), paired.get(e.boxId()),
                        e.encryptionKeys() != null,
                        local.partnerOf(e.boxId()).map(r -> r.partner().organization()).orElse(null),
                        local.boxRefusal(me.boxId(), e.boxId()).orElse(null), e.announcedProtocols(),
                        local.protocol(e.boxId()).orElse(null)))
                .toList();
        Map<String, String> names = new HashMap<>();
        List<PartnerView> partners = local.recognitions().stream()
                .sorted(Comparator.comparing(r -> r.partner().organization()))
                .map(r -> {
                    String fp = r.partner().fingerprint();
                    names.put(fp, r.partner().organization() + " (" + r.partner().shortCode() + ")");
                    LocalDirectory.SyncStatus ps = local.partnerSync(fp);
                    return new PartnerView(r.partner().organization(), r.partner().shortCode(), r.statusAt(now),
                            r.validUntil(), r.scope().localGroups(), r.scope().partnerGroups(), r.partner().relays(),
                            ps == null ? null : new SyncView(ps.at(), ps.accepted(), ps.rejected(), ps.error()),
                            (int) boxes.stream().filter(b -> b.organization() != null && local.partnerOf(b.boxId())
                                    .map(p -> p.partner().fingerprint().equals(fp)).orElse(false)).count(),
                            freshness(local, fp));
                })
                .toList();
        List<CollisionView> collisions = local.collisions().stream()
                .map(c -> new CollisionView(c.boxId(), names.getOrDefault(c.claimant(), c.claimant()),
                        LocalDirectory.LOCAL.equals(c.holder()) ? "cette organisation"
                                : names.getOrDefault(c.holder(), c.holder()), c.at()))
                .toList();
        return new DirectoryView(true, me.boxId(), me.keys().publicKey().shortCode(),
                new SyncView(sync.at(), sync.accepted(), sync.rejected(), sync.error()), boxes, partners, collisions,
                freshness(local, local.localFingerprint().orElse(null)));
    }

    /** @param name nom proposé pour la box (par défaut, le nom de la box) */
    @GetMapping(path = "/enrollment-request", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> enrollmentRequest(@RequestParam(required = false) String name) {
        BoxIdentity me = identity.getIfAvailable();
        if (me == null) {
            throw new NoSuchElementException("Annuaire non activé sur cette box");
        }
        String displayName = name == null || name.isBlank() ? box.name() : name;
        String request = enrollmentRequest(me, transport, displayName, clock).toJson();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"demande-" + me.boxId() + ".json\"")
                .body(request);
    }

    /**
     * Demande d'enrôlement de la box, signée par elle, avec ses adresses de contact (si le transport peut les donner).
     * Sert au téléchargement depuis la console et à l'envoi automatique à l'annuaire.
     */
    public static SignedDocument enrollmentRequest(BoxIdentity me, TransportPort transport, String displayName,
                                                   Clock clock) {
        Map<String, String> addresses = new HashMap<>();
        try {
            addresses.putAll(transport.contactAddresses());
        } catch (TransportException e) {
            LOG.log(System.Logger.Level.WARNING, "Demande d''enrôlement sans adresse de contact : {0}", e.getMessage());
        }
        return EnrollmentRequest.create(me.keys(), me.encryption().publicKey(), me.boxId(), displayName, addresses,
                clock.instant());
    }

    /**
     * Se connecte à l'adresse publiée d'une box active, de l'organisation ou d'un partenaire dont l'accord couvre les
     * deux box, sous le nom choisi par l'opérateur.
     */
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
        local.boxRefusal(me.boxId(), boxId).ifPresent(refusal -> {
            throw new IllegalStateException(refusal);
        });
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
