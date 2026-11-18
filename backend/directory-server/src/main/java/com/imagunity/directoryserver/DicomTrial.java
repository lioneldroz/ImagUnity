package com.imagunity.directoryserver;

import com.imagunity.directory.CentralConfiguration;
import com.imagunity.directory.ConfigurationTrial;
import com.imagunity.rules.DicomRouting;
import com.imagunity.rules.RuleSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dcm4che3.data.Attributes;

/** Essai d'une configuration DICOM avec le code de routage de la box, pour l'interface du responsable. */
public final class DicomTrial implements ConfigurationTrial {

    @Override
    public Result outgoing(CentralConfiguration.Dicom dicom, String node, String calledAet,
                           Map<String, String> fields) {
        DicomRouting routing = ConfigurationService.compile(dicom);
        DicomRouting.Node source = routing.nodes().get(node);
        if (source == null || !source.source()) {
            throw new IllegalArgumentException("« " + node + " » n'est pas un appareil source de cette configuration");
        }
        String called = calledAet == null || calledAet.isBlank() ? routing.aet() : calledAet.strip();
        Attributes dataset = RuleSet.dataset(fields);
        List<DicomRouting.Outgoing> routes = routing.outgoing();
        return routing.outgoingFor(source.aet(), called, dataset).map(r -> new Result(r.name(),
                routes.indexOf(r) + 1, r.to().stream().map(UUID::toString).toList()))
                .orElse(new Result(null, 0, List.of()));
    }

    @Override
    public Result incoming(CentralConfiguration.Dicom dicom, UUID origin, Map<String, String> fields) {
        DicomRouting routing = ConfigurationService.compile(dicom);
        Attributes dataset = RuleSet.dataset(fields);
        List<DicomRouting.Incoming> routes = routing.incoming();
        return routing.incomingFor(origin, dataset).map(r -> new Result(r.name(), routes.indexOf(r) + 1, r.to()))
                .orElse(new Result(null, 0, List.of()));
    }
}
