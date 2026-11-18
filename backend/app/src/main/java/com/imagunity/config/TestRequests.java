package com.imagunity.config;

import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.SignedDocument;
import com.imagunity.directory.TestRequest;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demandes d'essai de connexion du responsable (« Tester maintenant »), lues dans l'annuaire : la box ne suit qu'une
 * demande signée par la clé de l'organisation, qui la vise, récente, et pas déjà traitée.
 */
final class TestRequests {

    private static final Logger LOG = LoggerFactory.getLogger(TestRequests.class);

    private final UUID self;
    private final String organization;
    private final HybridPublicKey organizationKey;
    private final Clock clock;
    private volatile String handled;

    TestRequests(UUID self, String organization, HybridPublicKey organizationKey, Clock clock) {
        this.self = self;
        this.organization = organization;
        this.organizationKey = organizationKey;
        this.clock = clock;
    }

    /** Identifiant d'une demande nouvelle et recevable pour cette box, à traiter maintenant. */
    Optional<String> next(DirectoryClient client) {
        Optional<SignedDocument> document;
        try {
            document = client.fetchTestRequest(self);
        } catch (DirectoryException e) {
            LOG.debug("Demande d'essai non lue : {}", e.getMessage());
            return Optional.empty();
        }
        if (document.isEmpty()) {
            return Optional.empty();
        }
        TestRequest request;
        try {
            request = TestRequest.verify(document.get(), organization, organizationKey, clock.instant());
        } catch (DirectoryException e) {
            LOG.debug("Demande d'essai écartée : {}", e.getMessage());
            return Optional.empty();
        }
        if (!self.equals(request.boxId()) || request.id().equals(handled)) {
            return Optional.empty();
        }
        handled = request.id();
        LOG.info("Demande d'essai de connexion du responsable ({}) : essai de chaque destination", request.id());
        return Optional.of(request.id());
    }
}
