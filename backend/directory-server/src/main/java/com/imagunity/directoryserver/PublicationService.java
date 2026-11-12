package com.imagunity.directoryserver;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import java.time.Clock;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dépôt d'une entrée par le responsable. Le service revérifie la signature et refuse les retours en arrière ; les box
 * revérifient tout de leur côté, le service n'étant pas de confiance.
 */
public class PublicationService {

    private static final Logger LOG = LoggerFactory.getLogger(PublicationService.class);

    /** Résultat d'un dépôt. */
    public enum Outcome {
        /** Nouvelle entrée publiée. */
        PUBLISHED,
        /** Entrée déjà publiée à l'identique. */
        UNCHANGED
    }

    /** Dépôt refusé : entrée invalide (signature, organisation…). */
    public static final class InvalidEntryException extends RuntimeException {
        InvalidEntryException(String message) {
            super(message);
        }
    }

    /** Dépôt refusé : retour en arrière, ou autre entrée sous le même numéro. */
    public static final class ConflictException extends RuntimeException {
        ConflictException(String message) {
            super(message);
        }
    }

    private final EntryStore store;
    private final EntryVerifier verifier;
    private final Clock clock;

    public PublicationService(EntryStore store, String organization, HybridPublicKey organizationKey, Clock clock) {
        this.store = store;
        // Le contrôle des numéros se fait ici, contre la base : la mémoire du vérificateur reste vide.
        this.verifier = new EntryVerifier(organization, organizationKey, noMemory(), clock);
        this.clock = clock;
    }

    @Transactional
    public Outcome publish(SignedDocument document) {
        BoxEntry entry;
        try {
            entry = verifier.verify(document).entry();
        } catch (DirectoryException e) {
            throw new InvalidEntryException(e.getMessage());
        }
        OptionalLong highest = store.highestSerial(entry.boxId());
        if (highest.isPresent() && entry.serial() < highest.getAsLong()) {
            throw new ConflictException("Retour en arrière : la box " + entry.boxId() + " est déjà au n° "
                    + highest.getAsLong());
        }
        if (highest.isPresent() && entry.serial() == highest.getAsLong()) {
            if (store.find(entry.boxId(), entry.serial()).orElseThrow().equals(document)) {
                return Outcome.UNCHANGED;
            }
            throw new ConflictException("Une autre entrée n° " + entry.serial() + " est déjà publiée pour la box "
                    + entry.boxId());
        }
        store.save(entry, document, clock.instant());
        LOG.info("Entrée publiée : box {} « {} », n° {}{}", entry.boxId(), entry.displayName(), entry.serial(),
                entry.revoked() ? " (révocation)" : "");
        return Outcome.PUBLISHED;
    }

    private static SerialMemory noMemory() {
        return new SerialMemory() {
            @Override
            public OptionalLong highest(UUID boxId) {
                return OptionalLong.empty();
            }

            @Override
            public void record(UUID boxId, long serial) {
                // contrôlé contre la base
            }
        };
    }
}
