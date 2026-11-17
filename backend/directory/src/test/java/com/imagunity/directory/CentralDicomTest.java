package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bloc DICOM des documents de configuration centralisée : forme, signature, lecture tolérante. */
class CentralDicomTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final HybridKeyPair KEYS = HybridKeyPair.generate();
    private static final Organization ORG = new Organization("chu-exemple", KEYS);
    private static final String B = UUID.randomUUID().toString();
    private static final CentralConfiguration.Node PACS = new CentralConfiguration.Node("pacs-nord", "PACS_NORD",
            "10.2.0.15", 104, true, true);
    private static final CentralConfiguration.Node CT = new CentralConfiguration.Node("ct-urg", "CT_URG", null, null,
            true, false);

    private static CentralConfiguration.Route out(String name, List<String> from, List<String> to) {
        return new CentralConfiguration.Route(name, from, null, Map.of("Modality", "CT"), null, to);
    }

    private static CentralConfiguration draft(CentralConfiguration.Dicom dicom) {
        return CentralConfiguration.draft(ORG.id(), CentralConfiguration.Target.of(List.of(UUID.randomUUID())), 3, NOW,
                "responsable", "nœuds du site", null, null, dicom);
    }

    private static CentralConfiguration.Dicom dicom(List<CentralConfiguration.Node> nodes,
                                                    List<CentralConfiguration.Route> outgoing,
                                                    List<CentralConfiguration.Route> incoming) {
        return new CentralConfiguration.Dicom("IMAGUNITY_NORD", nodes, outgoing, incoming);
    }

    @Test
    void aDicomBlockIsSignedVerifiedAndDescribed() {
        CentralConfiguration c = draft(dicom(List.of(PACS, CT), List.of(out("ct-vers-b", List.of("ct-urg"), List.of(B))),
                List.of(new CentralConfiguration.Route("tout", null, null, null, null, List.of("pacs-nord")))));
        SignedDocument signed = c.sign(KEYS);
        CentralConfiguration read = CentralConfiguration.verify(signed, ORG.id(), ORG.publicKey(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(read.dicom()).isEqualTo(c.dicom());
        assertThat(read.dicom().node("ct-urg")).contains(CT);
        assertThat(read.dicom().node("autre")).isEmpty();
        assertThat(read.describe()).contains("DICOM : 2 nœud(s), 1 route(s) sortante(s), 1 route(s) entrante(s)");
        assertThat(signed.payload()).doesNotContain("\"host\":null").doesNotContain("\"calledAet\"");
        assertThat(new CentralConfiguration.Dicom(" ", null, null, null).aet()).isNull();
        assertThat(new CentralConfiguration.Route("r", null, " ", null, null, null).calledAet()).isNull();

        // Document antérieur, sans bloc DICOM : se lit encore ; un bloc seul suffit à faire un document.
        CentralConfiguration old = new CentralConfiguration(CentralConfiguration.TYPE, "IU1", ORG.id(),
                CentralConfiguration.Target.everyone(), 1, NOW, null, null, null,
                new CentralConfiguration.Routing(List.of(new CentralConfiguration.Rule("r", null, Map.of("Modality",
                        "CT"), null, List.of("box-b")))));
        assertThat(old.dicom()).isNull();
        assertThat(old.toJson()).doesNotContain("dicom");
        assertThatThrownBy(() -> CentralConfiguration.draft(ORG.id(), CentralConfiguration.Target.everyone(), 1, NOW,
                null, null, null, null, null)).hasMessageContaining("ni configuration DICOM");
    }

    @Test
    void anInvalidDicomBlockIsRefusedWithTheReason() {
        record Case(String expected, CentralConfiguration.Dicom dicom) {
        }
        List<CentralConfiguration.Node> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            many.add(new CentralConfiguration.Node("n" + i, "AET" + i, null, null, true, false));
        }
        List<Case> cases = List.of(
                new Case("AET de la box", new CentralConfiguration.Dicom("TROP_LONG_POUR_UN_AET", List.of(), List.of(),
                        List.of())),
                new Case("au plus", new CentralConfiguration.Dicom(null, many, List.of(), List.of())),
                new Case("nom invalide", dicom(List.of(new CentralConfiguration.Node("é", "A", null, null, true,
                        false)), List.of(), List.of())),
                new Case("en double", dicom(List.of(PACS, PACS), List.of(), List.of())),
                new Case("AET invalide", dicom(List.of(new CentralConfiguration.Node("x", "A B", null, null, true,
                        false)), List.of(), List.of())),
                new Case("ni source ni destination", dicom(List.of(new CentralConfiguration.Node("x", "X", null, null,
                        false, false)), List.of(), List.of())),
                new Case("adresse invalide", dicom(List.of(new CentralConfiguration.Node("x", "X", "pacs nord", null,
                        true, false)), List.of(), List.of())),
                new Case("adresse invalide", dicom(List.of(new CentralConfiguration.Node("x", "X", " ", null,
                        true, false)), List.of(), List.of())),
                new Case("une adresse et un port", dicom(List.of(new CentralConfiguration.Node("x", "X", "10.0.0.1",
                        null, false, true)), List.of(), List.of())),
                new Case("port hors", dicom(List.of(new CentralConfiguration.Node("x", "X", "10.0.0.1", 70000, true,
                        true)), List.of(), List.of())),
                new Case("Deux nœuds sources", dicom(List.of(CT, new CentralConfiguration.Node("ct2", "CT_URG", null,
                        null, true, false)), List.of(), List.of())),
                new Case("nom invalide", dicom(List.of(CT), List.of(out(null, List.of(), List.of(B))), List.of())),
                new Case("Route sortante en double", dicom(List.of(CT), List.of(out("r", List.of(), List.of(B)),
                        out("r", List.of(), List.of(B))), List.of())),
                new Case("aucune destination", dicom(List.of(CT), List.of(out("r", List.of(), List.of())), List.of())),
                new Case("n'est pas un nœud source", dicom(List.of(PACS, CT), List.of(out("r", List.of("absent"),
                        List.of(B))), List.of())),
                new Case("AET invalide", dicom(List.of(CT), List.of(new CentralConfiguration.Route("r", null, "A B",
                        null, null, List.of(B))), List.of())),
                new Case("identifiant de box invalide", dicom(List.of(CT), List.of(out("r", List.of(),
                        List.of("box-b"))), List.of())),
                new Case("identifiant de box invalide", dicom(List.of(PACS), List.of(), List.of(
                        new CentralConfiguration.Route("r", List.of("x"), null, null, null, List.of("pacs-nord"))))),
                new Case("réservé aux routes sortantes", dicom(List.of(PACS), List.of(), List.of(
                        new CentralConfiguration.Route("r", null, "IU", null, null, List.of("pacs-nord"))))),
                new Case("n'est pas un nœud destination", dicom(List.of(PACS, CT), List.of(), List.of(
                        new CentralConfiguration.Route("r", null, null, null, null, List.of("ct-urg"))))));
        for (Case c : cases) {
            assertThatThrownBy(() -> draft(c.dicom())).as(c.expected()).hasMessageContaining(c.expected());
        }
    }
}
