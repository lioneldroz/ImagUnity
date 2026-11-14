package com.imagunity.directoryserver;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.EnrollmentSubmission;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.SerialMemory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demandes d'enrôlement envoyées par les box, en attente de la signature du responsable.
 *
 * <p>Le service ne les signe pas et ne peut pas les authentifier : il vérifie seulement que chaque demande est signée
 * par les clés qu'elle présente (preuve de possession), puis la garde pour l'outil du responsable, qui reconnaît les
 * demandes authentiques à leur code d'enrôlement. Garde-fous contre l'abus de ce dépôt ouvert : une demande par box
 * (la plus récente remplace la précédente), pas de demande pour une box déjà active, nombre de demandes plafonné,
 * demandes effacées après {@link #KEPT} sans suite.
 */
public class EnrollmentService {

    private static final Logger LOG = LoggerFactory.getLogger(EnrollmentService.class);

    /** Durée de conservation d'une demande sans suite. */
    static final Duration KEPT = Duration.ofDays(14);
    /** Nombre maximal de demandes en attente. */
    static final int MAX_PENDING = 500;

    /** Trop de demandes en attente : 429. */
    public static final class TooManyException extends RuntimeException {
        TooManyException(String message) {
            super(message);
        }
    }

    /**
     * Demande en attente, pour l'interface et l'outil du responsable.
     *
     * @param withCode   vrai si la box a joint la preuve d'un code d'enrôlement (vérifiée par l'outil du responsable)
     * @param submission demande transmise, telle que reçue
     */
    public record Pending(UUID boxId, String displayName, String fingerprint, boolean withCode, Instant receivedAt,
                          EnrollmentSubmission submission) {
    }

    private final EnrollmentStore store;
    private final EntryStore entries;
    private final EntryVerifier verifier;
    private final Clock clock;

    public EnrollmentService(EnrollmentStore store, EntryStore entries, String organization,
                             HybridPublicKey organizationKey, Clock clock) {
        this.store = store;
        this.entries = entries;
        this.verifier = new EntryVerifier(organization, organizationKey, SerialMemory.inMemory(), clock);
        this.clock = clock;
    }

    /**
     * Enregistre la demande d'une box.
     *
     * @throws PublicationService.InvalidEntryException si elle est illisible ou mal signée (422)
     * @throws PublicationService.ConflictException     si la box est déjà active dans l'annuaire (409)
     * @throws TooManyException                         si trop de demandes attendent (429)
     */
    @Transactional
    public void submit(String body) {
        EnrollmentSubmission submission;
        EnrollmentRequest request;
        try {
            submission = EnrollmentSubmission.fromJson(body);
            request = EnrollmentRequest.verify(submission.request());
        } catch (DirectoryException e) {
            throw new PublicationService.InvalidEntryException(e.getMessage());
        }
        if (request.displayName().isBlank() || request.displayName().length() > PublicationService.MAX_DISPLAY_NAME) {
            throw new PublicationService.InvalidEntryException("Nom de box vide ou trop long ("
                    + PublicationService.MAX_DISPLAY_NAME + " caractères au plus)");
        }
        if (active(request.boxId())) {
            throw new PublicationService.ConflictException("Box " + request.boxId() + " déjà enrôlée");
        }
        Instant now = clock.instant();
        store.purgeBefore(now.minus(KEPT));
        if (!store.exists(request.boxId()) && store.count() >= MAX_PENDING) {
            throw new TooManyException("Trop de demandes d'enrôlement en attente (" + MAX_PENDING + ")");
        }
        boolean known = store.exists(request.boxId());
        store.save(request.boxId(), request.displayName(),
                HybridPublicKey.decode(request.signingKeys()).shortCode(), submission, now);
        if (!known) {
            LOG.info("Demande d'enrôlement reçue : box {} « {} »{}", request.boxId(), request.displayName(),
                    submission.code() == null ? ", sans code d'enrôlement" : "");
        }
    }

    /** Demandes en attente, de la plus récente à la plus ancienne. */
    public List<Pending> pending() {
        store.purgeBefore(clock.instant().minus(KEPT));
        return store.all().stream().map(p -> new Pending(p.boxId(), p.displayName(), p.fingerprint(),
                p.codeId() != null, p.receivedAt(), EnrollmentSubmission.fromJson(p.submission()))).toList();
    }

    /** L'entrée de la box vient d'être publiée : sa demande n'attend plus. */
    void handled(UUID boxId) {
        store.delete(boxId);
    }

    private boolean active(UUID boxId) {
        return entries.current(boxId).map(document -> {
            try {
                return verifier.verify(document).entry().statusAt(clock.instant()) == BoxEntry.Status.ACTIVE;
            } catch (DirectoryException e) {
                return false;
            }
        }).orElse(false);
    }
}
