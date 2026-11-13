package com.imagunity.directory;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plus haut numéro d'émission vu pour chaque box : une entrée de numéro inférieur est un retour en arrière (un
 * annuaire compromis qui resservirait l'entrée d'avant une révocation). La box le garde en base (étape C).
 */
public interface SerialMemory {

    OptionalLong highest(UUID boxId);

    void record(UUID boxId, long serial);

    /**
     * Empreinte du contenu de l'entrée retenue au plus haut numéro, si elle est connue : une autre entrée du même
     * numéro est refusée (deux documents signés du même numéro ne peuvent pas être servis tour à tour).
     */
    default Optional<String> digest(UUID boxId) {
        return Optional.empty();
    }

    /** Retient le numéro et, s'il devient le plus haut ou s'il n'en avait pas, l'empreinte de son contenu. */
    default void record(UUID boxId, long serial, String digest) {
        record(boxId, serial);
    }

    /** Mémoire en mémoire vive (tests, outil du responsable). */
    static SerialMemory inMemory() {
        record Seen(long serial, String digest) {
        }
        Map<UUID, Seen> serials = new ConcurrentHashMap<>();
        return new SerialMemory() {
            @Override
            public OptionalLong highest(UUID boxId) {
                Seen seen = serials.get(boxId);
                return seen == null ? OptionalLong.empty() : OptionalLong.of(seen.serial());
            }

            @Override
            public Optional<String> digest(UUID boxId) {
                return Optional.ofNullable(serials.get(boxId)).map(Seen::digest);
            }

            @Override
            public void record(UUID boxId, long serial) {
                record(boxId, serial, null);
            }

            @Override
            public void record(UUID boxId, long serial, String digest) {
                serials.merge(boxId, new Seen(serial, digest), (old, now) -> now.serial() > old.serial()
                        || now.serial() == old.serial() && old.digest() == null ? now : old);
            }
        };
    }
}
