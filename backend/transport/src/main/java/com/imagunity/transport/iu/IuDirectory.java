package com.imagunity.transport.iu;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.HybridKemPublicKey;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.transport.TransportException;
import java.time.Clock;
import java.util.List;
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
     * @param address       adresse de sa file de réception sur son relais principal ({@code transport.iu}), ou
     *                      {@code null}
     * @param addresses     adresses de ses files sur chaque relais ({@code iu}, {@code iu-2}, {@code iu-3}…), dans
     *                      l'ordre ; la première est {@code address}
     */
    record Partner(UUID boxId, String displayName, boolean active, HybridPublicKey signingKey,
                   HybridKemPublicKey encryptionKey, String address, List<String> addresses) {

        public Partner {
            addresses = addresses == null ? (address == null ? List.of() : List.of(address)) : List.copyOf(addresses);
            if (address == null && !addresses.isEmpty()) {
                address = addresses.getFirst();
            }
        }

        /** Box à une seule file. */
        public Partner(UUID boxId, String displayName, boolean active, HybridPublicKey signingKey,
                       HybridKemPublicKey encryptionKey, String address) {
            this(boxId, displayName, active, signingKey, encryptionKey, address, null);
        }
    }

    /** Clés {@code transport} des adresses de file : {@code iu}, {@code iu-2}, {@code iu-3}… */
    java.util.regex.Pattern ADDRESS_KEY = java.util.regex.Pattern.compile("iu(?:-([2-9]|[1-9][0-9]))?");

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
                        .filter(e -> addresses(e).contains(address))
                        .findFirst().map(this::partner);
            }

            private Partner partner(BoxEntry entry) {
                List<String> all = addresses(entry);
                return new Partner(entry.boxId(), entry.displayName(),
                        entry.statusAt(clock.instant()) == BoxEntry.Status.ACTIVE, entry.signingKey(),
                        entry.encryptionKey().orElse(null), normalized(entry.transport().get(IuTransport.ADDRESS_KIND)),
                        all);
            }
        };
    }

    /** Adresses de file d'une entrée, valides, dans l'ordre des clés ({@code iu}, puis {@code iu-2}…). */
    private static List<String> addresses(BoxEntry entry) {
        record Keyed(int rank, String address) {
        }
        List<Keyed> keyed = new java.util.ArrayList<>();
        entry.transport().forEach((key, value) -> {
            java.util.regex.Matcher m = ADDRESS_KEY.matcher(key);
            String address = normalized(value);
            if (m.matches() && address != null) {
                keyed.add(new Keyed(m.group(1) == null ? 1 : Integer.parseInt(m.group(1)), address));
            }
        });
        return keyed.stream().sorted(java.util.Comparator.comparingInt(Keyed::rank)).map(Keyed::address).toList();
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
