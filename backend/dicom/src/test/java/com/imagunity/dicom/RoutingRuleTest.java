package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

class RoutingRuleTest {

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
    void conditionsUseCaseInsensitiveWildcards() {
        RoutingRule rule = new RoutingRule("scanner-chu", Map.of("Modality", "ct", "institutionname", "CHU *"),
                List.of("box-b"));

        assertThat(rule.matches(instance("CT", "CHU Exemple"))).isTrue();
        assertThat(rule.matches(instance("CT", "  chu exemple  "))).as("espaces et casse ignorés").isTrue();
        assertThat(rule.matches(instance("MR", "CHU Exemple"))).isFalse();
        assertThat(rule.matches(instance("CT", "Clinique"))).isFalse();
        assertThat(rule.matches(instance("CT", null))).as("champ absent : chaîne vide").isFalse();
    }

    @Test
    void negationSingleCharacterMultipleValuesAndAbsentFields() {
        assertThat(new RoutingRule("pas-sr", Map.of("Modality", "!SR"), List.of("b")).matches(instance("CT", "x")))
                .isTrue();
        assertThat(new RoutingRule("pas-sr", Map.of("Modality", "!SR"), List.of("b")).matches(instance("SR", "x")))
                .isFalse();
        assertThat(new RoutingRule("deux", Map.of("Modality", "M?"), List.of("b")).matches(instance("MR", "x")))
                .isTrue();
        assertThat(new RoutingRule("axial", Map.of("ImageType", "axial"), List.of("b")).matches(instance("CT", "x")))
                .as("l'une des valeurs").isTrue();
        assertThat(new RoutingRule("sans", Map.of("InstitutionName", ""), List.of("b")).matches(instance("CT", null)))
                .isTrue();
        assertThat(new RoutingRule("avec", Map.of("InstitutionName", "!"), List.of("b")).matches(instance("CT", null)))
                .isFalse();
        assertThat(new RoutingRule("toujours", (Map<String, String>) null, List.of("b")).matches(instance("SR", null))).isTrue();
        assertThat(new RoutingRule("point", Map.of("StudyDescription", "a.b"), List.of("b"))
                .matches(withDescription("axb"))).as("caractères spéciaux pris tels quels").isFalse();
    }

    private static Attributes withDescription(String description) {
        Attributes attrs = instance("CT", null);
        attrs.setString(Tag.StudyDescription, VR.LO, description);
        return attrs;
    }

    @Test
    void invalidRulesAreRefused() {
        assertThatThrownBy(() -> new RoutingRule("x y", Map.of(), List.of("b"))).hasMessageContaining("Nom de règle");
        assertThatThrownBy(() -> new RoutingRule(null, Map.of(), List.of("b"))).hasMessageContaining("Nom de règle");
        assertThatThrownBy(() -> new RoutingRule("r", Map.of(), List.of())).hasMessageContaining("sans destinataire");
        assertThatThrownBy(() -> new RoutingRule("r", Map.of(), null)).hasMessageContaining("sans destinataire");
        assertThatThrownBy(() -> new RoutingRule("r", Map.of(), List.of("b", "b"))).hasMessageContaining("en double");
        assertThatThrownBy(() -> new RoutingRule("r", Map.of(), List.of("../b"))).hasMessageContaining("partenaire");
        assertThatThrownBy(() -> new RoutingRule("r", Map.of("Modalite", "CT"), List.of("b")))
                .hasMessageContaining("mot-clé DICOM inconnu « Modalite »");
        java.util.Map<String, String> nullPattern = new java.util.HashMap<>();
        nullPattern.put("Modality", null);
        assertThatThrownBy(() -> new RoutingRule("r", nullPattern, List.of("b"))).hasMessageContaining("motif absent");
        java.util.Map<String, String> nullKeyword = new java.util.HashMap<>();
        nullKeyword.put(null, "CT");
        assertThatThrownBy(() -> new RoutingRule("r", nullKeyword, List.of("b"))).hasMessageContaining("inconnu");
    }

    @Test
    void describeShowsConditionsWithoutPatientData() {
        assertThat(new RoutingRule("irm", Map.of("Modality", "MR"), List.of("box-c", "box-d")).describe())
                .isEqualTo("irm (Modality=MR) → box-c, box-d");
        assertThat(new RoutingRule("tout", Map.of(), List.of("box-b")).describe()).isEqualTo("tout (toujours) → box-b");
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
