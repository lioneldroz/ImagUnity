package com.imagunity.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Nœuds et routes DICOM de la configuration centralisée : compilation et choix de la route. */
class DicomRoutingTest {

    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final List<DicomRouting.NodeSpec> NODES = List.of(
            new DicomRouting.NodeSpec("pacs-nord", "PACS_NORD", "10.2.0.15", 104, true, true),
            new DicomRouting.NodeSpec("ct-urg", "CT_URG", null, null, true, false),
            new DicomRouting.NodeSpec("pacs-irm", "PACS_IRM", "10.2.0.16", 11112, false, true));

    private static DicomRouting.RouteSpec route(String name, List<String> from, String called, Map<String, String> and,
                                                List<String> to) {
        return new DicomRouting.RouteSpec(name, from, called, and, null, to);
    }

    private static DicomRouting routing() {
        return DicomRouting.compile("IMAGUNITY_NORD", NODES, List.of(
                        route("ct-vers-b", List.of("ct-urg"), null, Map.of("Modality", "CT"), List.of(B.toString())),
                        route("ancienne-route", List.of(), "IU_TO_C", Map.of(), List.of(C.toString())),
                        route("thorax", List.of("pacs-nord"), null, Map.of("SeriesDescription", "*THORAX*"),
                                List.of(B.toString(), C.toString()))),
                List.of(route("irm-de-b", List.of(B.toString()), null, Map.of("Modality", "MR"), List.of("pacs-irm")),
                        route("reste", List.of(), null, Map.of(), List.of("pacs-nord", "pacs-irm"))));
    }

    @Test
    void routesGoFromASourceToDestinations() {
        DicomRouting d = routing();
        assertThat(d.source("CT_URG")).hasValueSatisfying(n -> assertThat(n.name()).isEqualTo("ct-urg"));
        assertThat(d.source(" PACS_NORD ")).isPresent();
        assertThat(d.source("PACS_IRM")).as("destination seulement").isEmpty();
        assertThat(d.source(null)).isEmpty();
        assertThat(d.calledAets("LOCAL")).containsExactly("IMAGUNITY_NORD", "IU_TO_C");
        assertThat(d.outgoingBoxes()).containsExactly(B, C);

        var ct = RuleSet.dataset(Map.of("Modality", "CT", "SeriesDescription", "THORAX"));
        assertThat(d.outgoingFor("CT_URG", "IMAGUNITY_NORD", ct)).hasValueSatisfying(r -> assertThat(r.name())
                .isEqualTo("ct-vers-b"));
        assertThat(d.outgoingFor("PACS_NORD", "IMAGUNITY_NORD", ct)).hasValueSatisfying(r -> assertThat(r.to())
                .containsExactly(B, C));
        assertThat(d.outgoingFor("PACS_NORD", "IU_TO_C", RuleSet.dataset(Map.of("Modality", "MR"))))
                .hasValueSatisfying(r -> assertThat(r.name()).isEqualTo("ancienne-route"));
        assertThat(d.outgoingFor("PACS_NORD", "IMAGUNITY_NORD", RuleSet.dataset(Map.of("Modality", "MR"))))
                .as("aucune route").isEmpty();
        assertThat(d.outgoingFor(null, null, ct)).isEmpty();

        var mr = RuleSet.dataset(Map.of("Modality", "MR"));
        assertThat(d.incomingFor(B, mr)).hasValueSatisfying(r -> assertThat(r.to()).containsExactly("pacs-irm"));
        assertThat(d.incomingFor(C, mr)).hasValueSatisfying(r -> assertThat(r.name()).isEqualTo("reste"));
        assertThat(d.incomingFor(null, mr)).hasValueSatisfying(r -> assertThat(r.name()).isEqualTo("reste"));

        DicomRouting local = DicomRouting.compile(null, NODES, List.of(), List.of());
        assertThat(local.calledAets(" LOCAL ")).containsExactly("LOCAL");
        assertThat(local.calledAets(null)).isEmpty();
        assertThat(local.calledAets(" ")).isEmpty();
    }

    @Test
    void anInvalidConfigurationIsRefusedWithTheReason() {
        assertThatThrownBy(() -> DicomRouting.compile(null, List.of(NODES.getFirst(), NODES.getFirst()), List.of(),
                List.of())).hasMessageContaining("double");
        assertThatThrownBy(() -> DicomRouting.compile(null, NODES, List.of(route("r", List.of("pacs-irm"), null,
                Map.of(), List.of(B.toString()))), List.of())).hasMessageContaining("n'est pas un nœud source");
        assertThatThrownBy(() -> DicomRouting.compile(null, NODES, List.of(route("r", List.of("inconnu"), null,
                Map.of(), List.of(B.toString()))), List.of())).hasMessageContaining("inconnu");
        assertThatThrownBy(() -> DicomRouting.compile(null, NODES, List.of(route("r", List.of(), null,
                Map.of("PasUnMotCle", "x"), List.of(B.toString()))), List.of())).hasMessageContaining("PasUnMotCle");
        assertThatThrownBy(() -> DicomRouting.compile(null, NODES, List.of(route("r", List.of(), null, Map.of(),
                List.of("box-b"))), List.of())).hasMessageContaining("identifiant de box invalide");
        assertThatThrownBy(() -> DicomRouting.compile(null, NODES, List.of(), List.of(route("r", List.of(), null,
                Map.of(), List.of("ct-urg"))))).hasMessageContaining("n'est pas un nœud destination");
        assertThatThrownBy(() -> DicomRouting.compile(null, NODES, List.of(), List.of(route("r", List.of(), null,
                Map.of(), List.of("absent"))))).hasMessageContaining("absent");
        assertThatThrownBy(() -> DicomRouting.compile(null, NODES, List.of(), List.of(route("r", List.of("x"), null,
                Map.of(), List.of("pacs-nord"))))).hasMessageContaining("identifiant de box invalide");
    }
}
