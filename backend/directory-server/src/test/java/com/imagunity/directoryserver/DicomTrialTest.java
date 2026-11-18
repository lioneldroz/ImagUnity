package com.imagunity.directoryserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.CentralConfiguration;
import com.imagunity.directory.ConfigurationTrial;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DicomTrialTest {

    private static final UUID SUD = UUID.randomUUID();
    private static final UUID EST = UUID.randomUUID();

    private static final CentralConfiguration.Dicom DICOM = new CentralConfiguration.Dicom("IMAGUNITY_NORD", List.of(
            new CentralConfiguration.Node("pacs-nord", "PACS_NORD", "10.2.0.15", 104, true, true),
            new CentralConfiguration.Node("pacs-irm", "PACS_IRM", "10.2.0.16", 11112, false, true)),
            List.of(new CentralConfiguration.Route("urgences", List.of("pacs-nord"), "IU_URG", Map.of(), null,
                            List.of(EST.toString())),
                    new CentralConfiguration.Route("scanner", List.of("pacs-nord"), null, Map.of("Modality", "CT"),
                            null, List.of(SUD.toString()))),
            List.of(new CentralConfiguration.Route("irm", List.of(SUD.toString()), null, Map.of("Modality", "MR"),
                            null, List.of("pacs-irm")),
                    new CentralConfiguration.Route("reste", List.of(), null, Map.of(), null, List.of("pacs-nord"))));

    @Test
    void theRouteAnInstanceWouldFollowIsFoundByTheBoxCode() {
        ConfigurationTrial trial = ConfigurationTrial.available().orElseThrow();
        assertThat(trial).isInstanceOf(DicomTrial.class);
        assertThat(trial.outgoing(DICOM, "pacs-nord", "", Map.of("Modality", "CT")))
                .isEqualTo(new ConfigurationTrial.Result("scanner", 2, List.of(SUD.toString())));
        assertThat(trial.outgoing(DICOM, "pacs-nord", "IU_URG", Map.of("Modality", "MR")).route())
                .as("AET appelé exigé").isEqualTo("urgences");
        assertThat(trial.outgoing(DICOM, "pacs-nord", null, Map.of("Modality", "MR")).route()).isNull();
        assertThatThrownBy(() -> trial.outgoing(DICOM, "pacs-irm", null, Map.of()))
                .hasMessageContaining("n'est pas un appareil source");
        assertThatThrownBy(() -> trial.outgoing(DICOM, "absent", null, Map.of()))
                .hasMessageContaining("absent");
        assertThatThrownBy(() -> trial.outgoing(DICOM, "pacs-nord", null, Map.of("Modalite", "CT")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(trial.incoming(DICOM, SUD, Map.of("Modality", "MR")))
                .isEqualTo(new ConfigurationTrial.Result("irm", 1, List.of("pacs-irm")));
        assertThat(trial.incoming(DICOM, null, Map.of("Modality", "MR")).route()).isEqualTo("reste");
        assertThat(trial.incoming(new CentralConfiguration.Dicom(null, DICOM.nodes(), List.of(), List.of()), SUD,
                Map.of()).route()).isNull();
    }
}
