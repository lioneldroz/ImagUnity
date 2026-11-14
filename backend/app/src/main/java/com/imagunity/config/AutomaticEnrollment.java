package com.imagunity.config;

import com.imagunity.api.DirectoryController;
import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.EnrollmentSubmission;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.transport.TransportPort;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Envoi automatique de la demande d'enrôlement : tant que la box n'est pas dans l'annuaire, elle y dépose sa demande
 * signée, à chaque lecture de l'annuaire, avec la preuve de son code d'enrôlement s'il y en a un. Le responsable la
 * signe ensuite sur son poste ; dès que l'entrée est publiée, la box la voit et cesse d'envoyer.
 *
 * <p>Une box déjà présente dans l'annuaire, même expirée ou révoquée, n'envoie rien : le renouvellement et la
 * réintégration sont décidés par le responsable.
 */
final class AutomaticEnrollment {

    private static final Logger LOG = LoggerFactory.getLogger(AutomaticEnrollment.class);

    private final LocalDirectory directory;
    private final DirectoryClient client;
    private final BoxIdentity identity;
    private final TransportPort transport;
    private final String displayName;
    private final String code;
    private final Clock clock;
    private boolean announced;
    private boolean warned;

    /**
     * @param code code d'enrôlement ({@code null} ou vide : aucun)
     * @throws IllegalArgumentException si le code est mal formé (la box refuse de démarrer)
     */
    AutomaticEnrollment(LocalDirectory directory, DirectoryClient client, BoxIdentity identity, TransportPort transport,
                        String displayName, String code, Clock clock) {
        this.directory = directory;
        this.client = client;
        this.identity = identity;
        this.transport = transport;
        this.displayName = displayName;
        this.code = code == null || code.isBlank() ? null : code;
        if (this.code != null) {
            com.imagunity.directory.EnrollmentCode.normalize(this.code);
        }
        this.clock = clock;
    }

    /** Envoie la demande si la box n'est pas encore dans l'annuaire ; vrai si une demande a été envoyée. */
    synchronized boolean submitIfNeeded() {
        if (directory.entry(identity.boxId()).isPresent()) {
            return false;
        }
        EnrollmentSubmission submission = EnrollmentSubmission.of(
                DirectoryController.enrollmentRequest(identity, transport, displayName, clock), code);
        try {
            if (!client.submitEnrollment(submission)) {
                LOG.debug("Demande d'enrôlement : box déjà enrôlée selon l'annuaire, en attente de la lecture suivante");
                return false;
            }
        } catch (DirectoryException e) {
            if (!warned) {
                LOG.warn("Demande d'enrôlement non transmise à l'annuaire, nouvel essai à la lecture suivante : {}",
                        e.getMessage());
                warned = true;
            }
            return false;
        }
        if (!announced) {
            LOG.info("Demande d'enrôlement transmise à l'annuaire ({}) : en attente de la signature du responsable",
                    code == null ? "sans code d'enrôlement : le responsable vérifiera l'empreinte "
                            + identity.keys().publicKey().shortCode() : "avec la preuve du code d'enrôlement");
            announced = true;
        }
        return true;
    }
}
