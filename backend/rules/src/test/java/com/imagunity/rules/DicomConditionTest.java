package com.imagunity.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

/** Conditions DICOM : and, or, valeurs possibles, négation, champ absent, valeurs multiples. */
class DicomConditionTest {

    private static Attributes series(String modality, String description, String... imageType) {
        Attributes attrs = TestDatasets.ct("2.25.1", "2.25.1.1", "2.25.1.1.1");
        attrs.setString(Tag.Modality, VR.CS, modality);
        if (description != null) {
            attrs.setString(Tag.SeriesDescription, VR.LO, description);
        }
        if (imageType.length > 0) {
            attrs.setString(Tag.ImageType, VR.CS, imageType);
        }
        return attrs;
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void andRequiresEveryFieldOrOneOfThem() {
        DicomCondition thorax = DicomCondition.of("Règle t", fields("Modality", "CT",
                "SeriesDescription", "*THORAX*|*poumon*"), null);
        assertThat(thorax.matches(series("CT", "Thorax injecté"))).isTrue();
        assertThat(thorax.matches(series("CT", "Coupes POUMON"))).as("valeur possible, casse ignorée").isTrue();
        assertThat(thorax.matches(series("CT", "Abdomen"))).isFalse();
        assertThat(thorax.matches(series("MR", "Thorax"))).isFalse();

        DicomCondition scouts = DicomCondition.of("Règle s", null, fields("ImageType", "LOCALIZER",
                "SeriesDescription", "*SCOUT*|*TOPO*"));
        assertThat(scouts.matches(series("CT", "Axial", "ORIGINAL", "PRIMARY", "LOCALIZER"))).as("valeurs multiples")
                .isTrue();
        assertThat(scouts.matches(series("CT", "topogramme"))).isTrue();
        assertThat(scouts.matches(series("CT", "Axial", "ORIGINAL", "PRIMARY", "AXIAL"))).isFalse();

        DicomCondition both = DicomCondition.of("Règle b", fields("Modality", "CT"),
                fields("SeriesDescription", "*THORAX*", "BodyPartExamined", "CHEST"));
        assertThat(both.matches(series("CT", "Thorax"))).isTrue();
        assertThat(both.matches(series("MR", "Thorax"))).as("and toujours exigé").isFalse();
        assertThat(both.matches(series("CT", "Abdomen"))).as("aucun or").isFalse();
        assertThat(DicomCondition.ALWAYS.matches(series("SR", null))).isTrue();
    }

    @Test
    void prefixesContainsNegationsAndMissingFields() {
        assertThat(DicomCondition.of("r", fields("SeriesDescription", "Thor*"), null)
                .matches(series("CT", "Thorax"))).as("commence par").isTrue();
        assertThat(DicomCondition.of("r", fields("SeriesDescription", "Thor*"), null)
                .matches(series("CT", "Gros thorax"))).isFalse();
        assertThat(DicomCondition.of("r", fields("SeriesDescription", "  thorax  "), null)
                .matches(series("CT", "THORAX"))).as("valeur exacte, espaces et casse ignorés").isTrue();
        assertThat(DicomCondition.of("r", fields("Modality", "!SR|KO"), null).matches(series("SR", null)))
                .as("négation de plusieurs valeurs").isFalse();
        assertThat(DicomCondition.of("r", fields("Modality", "!SR|KO"), null).matches(series("CT", null))).isTrue();
        assertThat(DicomCondition.of("r", fields("SeriesDescription", ""), null).matches(series("CT", null)))
                .as("champ absent").isTrue();
        assertThat(DicomCondition.of("r", fields("SeriesDescription", "!"), null).matches(series("CT", null)))
                .as("champ présent").isFalse();
        assertThat(DicomCondition.of("r", fields("SeriesDescription", "|*THORAX*"), null).matches(series("CT", null)))
                .as("absent ou thorax").isTrue();
        assertThat(DicomCondition.of("r", fields("modality", "M?"), null).matches(series("MR", null))).isTrue();
    }

    @Test
    void unknownKeywordsAndMissingPatternsAreRefusedAndDescribed() {
        assertThatThrownBy(() -> DicomCondition.of("Règle de priorité p", fields("Modalite", "CT"), null))
                .hasMessageContaining("Règle de priorité p").hasMessageContaining("Modalite");
        Map<String, String> nullPattern = new HashMap<>();
        nullPattern.put("Modality", null);
        assertThatThrownBy(() -> DicomCondition.of("Règle o", null, nullPattern)).hasMessageContaining("motif absent");

        assertThat(DicomCondition.ALWAYS.describe()).isEqualTo("toujours");
        assertThat(DicomCondition.of("r", fields("Modality", "CT"), fields("SeriesDescription", "*SCOUT*",
                "ImageType", "LOCALIZER")).describe())
                .isEqualTo("Modality=CT, ou (SeriesDescription=*SCOUT* | ImageType=LOCALIZER)");
        assertThat(new RoutingRule("r", DicomCondition.of("r", null, fields("Modality", "CT|MR")), List.of("box-b"))
                .describe()).isEqualTo("r (ou (Modality=CT|MR)) → box-b");
        assertThat(new RoutingRule("r", (DicomCondition) null, List.of("box-b")).matches(series("SR", null))).isTrue();
    }
}
