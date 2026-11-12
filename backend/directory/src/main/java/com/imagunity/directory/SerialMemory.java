package com.imagunity.directory;

import java.util.Map;
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

    /** Mémoire en mémoire vive (tests, outil du responsable). */
    static SerialMemory inMemory() {
        Map<UUID, Long> serials = new ConcurrentHashMap<>();
        return new SerialMemory() {
            @Override
            public OptionalLong highest(UUID boxId) {
                Long serial = serials.get(boxId);
                return serial == null ? OptionalLong.empty() : OptionalLong.of(serial);
            }

            @Override
            public void record(UUID boxId, long serial) {
                serials.merge(boxId, serial, Math::max);
            }
        };
    }
}
