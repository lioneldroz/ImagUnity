package com.imagunity.directoryserver;

import com.imagunity.directory.Freshness;
import com.imagunity.directory.FreshnessDelegation;
import com.imagunity.directory.FreshnessProof;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.SignedDocument;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Preuve de fraîcheur de l'annuaire : état daté (plus haut numéro de chaque entrée et reconnaissance), signé par la
 * clé de fraîcheur que le responsable a déléguée au service. Re-signée quand l'état change, et au moins toutes les
 * {@code interval} ; sans clé configurée, l'annuaire n'en publie pas.
 */
public class FreshnessService {

    private static final Logger LOG = LoggerFactory.getLogger(FreshnessService.class);

    private record Signed(Map<UUID, Long> entries, Map<String, Long> recognitions, Freshness freshness, Instant at) {
    }

    private final EntryStore entries;
    private final RecognitionStore recognitions;
    private final String organization;
    private final HybridKeyPair key;
    private final SignedDocument delegation;
    private final Duration interval;
    private final Clock clock;
    private volatile Signed last;

    /**
     * @param key        clé de fraîcheur ; {@code null} : pas de preuve publiée
     * @param delegation délégation de cette clé, signée par le responsable
     * @throws IllegalStateException si la délégation n'est pas celle de cette clé et de cette organisation
     */
    public FreshnessService(EntryStore entries, RecognitionStore recognitions, String organization,
                            HybridPublicKey organizationKey, HybridKeyPair key, SignedDocument delegation,
                            Duration interval, Clock clock) {
        this.entries = entries;
        this.recognitions = recognitions;
        this.organization = organization;
        this.key = key;
        this.delegation = delegation;
        this.interval = interval;
        this.clock = clock;
        if (key != null) {
            Freshness check = new Freshness(delegation, FreshnessProof.sign(key, organization, clock.instant(), Map.of(),
                    Map.of()));
            FreshnessDelegation d = check.delegationContent();
            try {
                check.verify(organization, organizationKey, clock.instant());
            } catch (com.imagunity.directory.DirectoryException e) {
                throw new IllegalStateException("Clé de fraîcheur inutilisable : " + e.getMessage(), e);
            }
            Duration left = Duration.between(clock.instant(), d.validUntil());
            LOG.info("Preuves de fraîcheur signées par la clé déléguée n° {}, valable jusqu'au {}", d.serial(),
                    d.validUntil());
            if (left.compareTo(Duration.ofDays(7)) < 0) {
                LOG.warn("Délégation de la clé de fraîcheur proche de sa fin ({}) : la faire renouveler par le "
                        + "responsable (fraicheur-deleguer)", d.validUntil());
            }
        } else {
            LOG.warn("Aucune clé de fraîcheur configurée : l'annuaire ne publie pas de preuve de fraîcheur");
        }
    }

    /** Preuve courante ; vide sans clé de fraîcheur. */
    public synchronized Optional<Freshness> current() {
        if (key == null) {
            return Optional.empty();
        }
        Map<UUID, Long> e = entries.highestSerials();
        Map<String, Long> r = recognitions.highestSerials();
        Instant now = clock.instant();
        Signed s = last;
        if (s == null || !s.entries().equals(e) || !s.recognitions().equals(r) || !now.isBefore(s.at().plus(interval))) {
            s = new Signed(e, r, new Freshness(delegation, FreshnessProof.sign(key, organization, now, e, r)), now);
            last = s;
        }
        return Optional.of(s.freshness());
    }
}
