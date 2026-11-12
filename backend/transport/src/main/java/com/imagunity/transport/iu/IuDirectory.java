package com.imagunity.transport.iu;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.HybridKemPublicKey;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.transport.TransportException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ce que le transport IU-T lit dans l'annuaire signé de l'organisation (spécification § 9) : clés et adresse de file
 * de chaque box. Les entrées sont déjà vérifiées (signature du responsable, validité, révocation).
 */
public interface IuDirectory {

    /**
     * Une box de l'annuaire.
     *
     * @param boxId         identifiant
     * @param displayName   nom dans l'annuaire
     * @param active        active (ni révoquée, ni expirée, ni pas encore valide)
     * @param signingKey    clés de signature
     * @param encryptionKey clés de chiffrement, ou {@code null} si l'entrée n'en porte pas
     * @param address       adresse de sa file de réception IU-T ({@code transport.iu}), ou {@code null}
     */
    record Partner(UUID boxId, String displayName, boolean active, HybridPublicKey signingKey,
                   HybridKemPublicKey encryptionKey, String address) {
    }

    Optional<Partner> partner(UUID boxId);

    /** Box dont l'entrée publie cette adresse de file. */
    Optional<Partner> byAddress(String address);

    /** Lecture de la vue locale de l'annuaire de la box (entrées vérifiées). */
    static IuDirectory of(LocalDirectory directory, Clock clock) {
        return new IuDirectory() {
            @Override
            public Optional<Partner> partner(UUID boxId) {
                return directory.entry(boxId).map(this::partner);
            }

            @Override
            public Optional<Partner> byAddress(String address) {
                return directory.entries().stream()
                        .filter(e -> Objects.equals(normalized(e.transport().get(IuTransport.ADDRESS_KIND)), address))
                        .findFirst().map(this::partner);
            }

            private Partner partner(BoxEntry entry) {
                return new Partner(entry.boxId(), entry.displayName(),
                        entry.statusAt(clock.instant()) == BoxEntry.Status.ACTIVE, entry.signingKey(),
                        entry.encryptionKey().orElse(null), normalized(entry.transport().get(IuTransport.ADDRESS_KIND)));
            }
        };
    }

    /** Adresse sous sa forme canonique, ou {@code null} si absente ou invalide. */
    private static String normalized(String address) {
        if (address == null) {
            return null;
        }
        try {
            return QueueAddress.parse(address).toString();
        } catch (TransportException e) {
            return null;
        }
    }
}
