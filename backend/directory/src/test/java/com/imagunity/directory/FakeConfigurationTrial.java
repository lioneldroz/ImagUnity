package com.imagunity.directory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Essai factice pour les tests du module (le vrai, avec le code de routage, est dans le jar de l'annuaire). */
public final class FakeConfigurationTrial implements ConfigurationTrial {

    @Override
    public Result outgoing(CentralConfiguration.Dicom dicom, String node, String calledAet,
                           Map<String, String> fields) {
        if (fields.containsKey("Inconnu")) {
            throw new IllegalArgumentException("mot-clé DICOM inconnu : Inconnu");
        }
        return "CT".equals(fields.get("Modality")) ? new Result("ct", 1, List.of(node + "/" + calledAet))
                : new Result(null, 0, List.of());
    }

    @Override
    public Result incoming(CentralConfiguration.Dicom dicom, UUID origin, Map<String, String> fields) {
        return new Result("tout", 1, List.of(String.valueOf(origin)));
    }
}
