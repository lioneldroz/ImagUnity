package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.rules.RoutingRule;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

/** Routage par règles dans la configuration de l'entrée DICOM. */
class DicomInputRoutingTest {

    private static Attributes instance(String modality, String institution) {
        Attributes attrs = TestDatasets.ct("2.25.1", "2.25.1.1", "2.25.1.1.1");
        attrs.setString(Tag.Modality, VR.CS, modality);
        if (institution != null) {
            attrs.setString(Tag.InstitutionName, VR.LO, institution);
        }
        attrs.setString(Tag.ImageType, VR.CS, "ORIGINAL", "PRIMARY", "AXIAL");
        return attrs;
    }

    @Test
    void configurationChecksTheRoutingDestination() {
        RoutingRule ct = new RoutingRule("ct", Map.of("Modality", "CT"), List.of("box-b"));
        RoutingRule all = new RoutingRule("reste", Map.of(), List.of("box-c"));
        DicomInputConfig config = new DicomInputConfig(null, 11112, Set.of("A"), Map.of("IU_TO_B", "box-b"), Path.of("x"),
                Duration.ofSeconds(1), 0, " IU_AUTO ", List.of(ct, all));

        assertThat(config.isRoutingAet("IU_AUTO")).isTrue();
        assertThat(config.isRoutingAet(null)).isFalse();
        assertThat(config.isKnownCalledAet("IU_TO_B")).isTrue();
        assertThat(config.isKnownCalledAet("IU_AUTO ")).isTrue();
        assertThat(config.isKnownCalledAet("IU_TO_Z")).isFalse();
        assertThat(config.ruleFor(instance("CT", null))).contains(ct);
        assertThat(config.ruleFor(instance("MR", null))).as("première règle qui correspond").contains(all);

        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of("IU_AUTO", "box-b"), Path.of("x"),
                Duration.ofSeconds(1), 0, "IU_AUTO", List.of(ct))).hasMessageContaining("à la fois");
        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of(), Path.of("x"),
                Duration.ofSeconds(1), 0, "IU_AUTO", List.of())).hasMessageContaining("sans règle");
        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of(), Path.of("x"),
                Duration.ofSeconds(1), 0, null, List.of(ct))).hasMessageContaining("routing-aet");
        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of(), Path.of("x"),
                Duration.ofSeconds(1), 0, "IU AUTO", List.of(ct))).hasMessageContaining("routage invalide");
        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of(), Path.of("x"),
                Duration.ofSeconds(1), 0, "IU_AUTO", List.of(ct, ct))).hasMessageContaining("même nom");
        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of("IU_TO_B", "box b"),
                Path.of("x"), Duration.ofSeconds(1), 0)).hasMessageContaining("partenaire invalide");
        assertThat(new DicomInputConfig(null, 11112, Set.of(), Map.of(), Path.of("x"), Duration.ofSeconds(1), 0, " ",
                null).routingAet()).isNull();
    }
}
