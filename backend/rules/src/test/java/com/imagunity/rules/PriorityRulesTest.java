package com.imagunity.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.exam.ReceivedInstance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Règles de priorité : première règle qui correspond, priorité par défaut, séries aux instances divergentes. */
class PriorityRulesTest {

    @TempDir
    Path dir;

    private static Map<String, String> ordered(String... keyValues) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static final PriorityRules RULES = new PriorityRules(5, List.of(
            new PriorityRule("scanner-thorax", 1, new DicomCondition(
                    ordered("Modality", "CT", "SeriesDescription", "*THORAX*|*POUMON*"), null)),
            new PriorityRule("localisateurs-en-dernier", 9, new DicomCondition(null,
                    ordered("ImageType", "*LOCALIZER*", "SeriesDescription", "*SCOUT*|*TOPO*")))));

    private Path file(String name, String modality, String description, String... imageType) throws IOException {
        Attributes ds = TestDatasets.ct("2.25.1", "2.25.1.1", "2.25.1.1." + name.hashCode() % 1000);
        ds.setString(Tag.Modality, VR.CS, modality);
        ds.setString(Tag.SeriesDescription, VR.LO, description);
        if (imageType.length > 0) {
            ds.setString(Tag.ImageType, VR.CS, imageType);
        }
        Path file = dir.resolve(name + ".dcm");
        try (DicomOutputStream out = new DicomOutputStream(file.toFile())) {
            out.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds);
        }
        return file;
    }

    private static ReceivedInstance instance(Path file) throws IOException {
        return new ReceivedInstance("1.2", "1." + file.getFileName().hashCode(), "2.25.1.1", "1.2", file,
                Files.size(file));
    }

    @Test
    void theFirstMatchingRuleGivesThePriority() throws Exception {
        assertThat(RULES.of(List.of(instance(file("a", "CT", "Thorax injecté"))))).isEqualTo(1);
        assertThat(RULES.of(List.of(instance(file("b", "CT", "Topogramme"))))).isEqualTo(9);
        assertThat(RULES.of(List.of(instance(file("c", "MR", "Crâne"))))).as("par défaut").isEqualTo(5);
        assertThat(PriorityRules.NONE.of(List.of(instance(file("d", "CT", "Thorax"))))).isEqualTo(5);
    }

    @Test
    void theHighestPriorityOfASeriesWinsAndUnreadableInstancesUseTheDefault() throws Exception {
        ReceivedInstance scout = instance(file("e", "CT", "Axial", "ORIGINAL", "PRIMARY", "LOCALIZER"));
        ReceivedInstance plain = instance(file("f", "CT", "Axial", "ORIGINAL", "PRIMARY", "AXIAL"));
        assertThat(RULES.of(List.of(scout, plain))).as("défaut l'emporte sur 9").isEqualTo(5);
        assertThat(RULES.of(List.of(scout))).isEqualTo(9);
        ReceivedInstance thorax = instance(file("g", "CT", "Poumon"));
        assertThat(RULES.of(List.of(scout, thorax, plain))).isEqualTo(1);

        Path broken = Files.writeString(dir.resolve("illisible.dcm"), "pas du DICOM");
        assertThat(RULES.of(List.of(instance(broken)))).isEqualTo(5);
        assertThat(RULES.of(List.of())).isEqualTo(5);
    }

    @Test
    void invalidRulesAreRefusedAndDescribedWithoutPatientData() {
        assertThatThrownBy(() -> new PriorityRule("x", 0, null)).hasMessageContaining("hors de 1 à 9");
        assertThatThrownBy(() -> new PriorityRule("x", 10, null)).hasMessageContaining("hors de 1 à 9");
        assertThatThrownBy(() -> new PriorityRule("../x", 1, null)).hasMessageContaining("Nom");
        assertThatThrownBy(() -> new PriorityRule("x", 1, new DicomCondition(Map.of("Modalite", "CT"), null)))
                .hasMessageContaining("Règle de priorité x").hasMessageContaining("Modalite");
        assertThatThrownBy(() -> new PriorityRules(0, List.of())).hasMessageContaining("défaut");
        assertThatThrownBy(() -> new PriorityRules(5, List.of(new PriorityRule("a", 1, null),
                new PriorityRule("a", 2, null)))).hasMessageContaining("Deux règles");
        assertThat(new PriorityRules(5, null).rules()).isEmpty();
        assertThat(RULES.describe()).containsExactly(
                "scanner-thorax : priorité 1 (Modality=CT, SeriesDescription=*THORAX*|*POUMON*)",
                "localisateurs-en-dernier : priorité 9 (ou (ImageType=*LOCALIZER* | SeriesDescription=*SCOUT*|*TOPO*))");
        assertThat(new PriorityRule("tout", 3, null).matches(new Attributes())).isTrue();
    }
}
