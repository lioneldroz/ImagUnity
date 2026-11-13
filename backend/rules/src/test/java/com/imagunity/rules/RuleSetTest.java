package com.imagunity.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Règles de la configuration centralisée : compilation et essai sur un en-tête synthétique. */
class RuleSetTest {

    private static final List<RuleSet.Spec> PRIORITY = List.of(
            new RuleSet.Spec("thorax", 1, Map.of("Modality", "CT", "SeriesDescription", "*THORAX*"), null, null),
            new RuleSet.Spec("localisateurs", 9, null, Map.of("ImageType", "*LOCALIZER*"), null));
    private static final List<RuleSet.Spec> ROUTING = List.of(
            new RuleSet.Spec("chu", null, Map.of("InstitutionName", "CHU*"), null, List.of("box-b", "box-c")));

    @Test
    void rulesAreCompiledAndTriedOnASyntheticHeader() {
        RuleSet rules = RuleSet.compile(4, PRIORITY, ROUTING);
        assertThat(rules.priority().defaultPriority()).isEqualTo(4);
        RuleSet.Trial thorax = rules.trial(RuleSet.dataset(Map.of("Modality", "CT", "SeriesDescription",
                "THORAX 1.25", "InstitutionName", "CHU Exemple")));
        assertThat(thorax).isEqualTo(new RuleSet.Trial(1, "thorax", "chu", List.of("box-b", "box-c")));
        RuleSet.Trial scout = rules.trial(RuleSet.dataset(Map.of("ImageType", "ORIGINAL\\PRIMARY\\LOCALIZER")));
        assertThat(scout.priority()).isEqualTo(9);
        assertThat(scout.routingRule()).isNull();
        assertThat(scout.destinations()).isEmpty();
        assertThat(rules.trial(RuleSet.dataset(Map.of("Modality", "MR"))).priority()).as("par défaut").isEqualTo(4);

        RuleSet onlyRouting = RuleSet.compile(null, null, ROUTING);
        assertThat(onlyRouting.priority()).isNull();
        assertThat(onlyRouting.trial(RuleSet.dataset(Map.of())).priority()).isZero();
        assertThat(RuleSet.compile(null, List.of(), null).priority().defaultPriority()).isEqualTo(5);
        assertThat(RuleSet.compile(null, List.of(), null).routing()).isNull();
    }

    @Test
    void invalidRulesAreRefusedWithTheReason() {
        assertThatThrownBy(() -> RuleSet.compile(null, List.of(new RuleSet.Spec("x", null, null, null, null)), null))
                .hasMessageContaining("sans priorité");
        assertThatThrownBy(() -> RuleSet.compile(null, List.of(new RuleSet.Spec("x", 1, Map.of("Modalite", "CT"),
                null, null)), null)).hasMessageContaining("inconnu");
        assertThatThrownBy(() -> RuleSet.compile(null, null, List.of(ROUTING.getFirst(), ROUTING.getFirst())))
                .hasMessageContaining("Deux règles");
        assertThatThrownBy(() -> RuleSet.compile(null, null, List.of(new RuleSet.Spec("x", null, null, null,
                List.of("pas valide!"))))).hasMessageContaining("partenaire invalide");
        assertThatThrownBy(() -> RuleSet.dataset(Map.of("Inconnu", "x"))).hasMessageContaining("inconnu");
        assertThatThrownBy(() -> RuleSet.dataset(Map.of("ReferencedSeriesSequence", "x")))
                .hasMessageContaining("séquence");
        assertThat(RuleSet.dataset(java.util.Collections.singletonMap("PatientID", null)).getString(
                org.dcm4che3.data.Tag.PatientID)).isNull();
    }
}
