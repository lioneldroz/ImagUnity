package com.imagunity.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scénarios de routage et d'entrée DICOM, avec trois établissements : A envoie, B et C reçoivent.
 *
 * <ul>
 *   <li>destination DICOM fixe ({@code IU_TO_B}, {@code IU_TO_C}) ;</li>
 *   <li>routage par champ DICOM sur {@code IU_AUTO} : scanner du CHU vers B, IRM vers B et C, le reste refusé ;</li>
 *   <li>émetteur non déclaré, destination inconnue, partenaire jamais appairé : refus, rien ne part.</li>
 * </ul>
 */
class RoutingScenarioTest {

    private static Bench bench;
    private static Box a;
    private static Box b;
    private static Box c;

    @BeforeAll
    static void start() {
        bench = new Bench();
        a = bench.box(Box.Spec.of("box-a")
                .route("IU_TO_B", "box-b").route("IU_TO_C", "box-c").route("IU_TO_D", "box-d")
                .routing("IU_AUTO",
                        new Box.Rule("scanner-chu", Map.of("Modality", "CT", "InstitutionName", "CHU*"),
                                List.of("box-b")),
                        new Box.Rule("irm", Map.of("Modality", "MR"), List.of("box-b", "box-c"))));
        b = bench.box(Box.Spec.of("box-b"));
        c = bench.box(Box.Spec.of("box-c"));
        bench.enroll(a);
        bench.enroll(b);
        bench.enroll(c);
        bench.pair(a, b, "box-b");
        bench.pair(a, c, "box-c");
    }

    @AfterAll
    static void stop() {
        if (bench != null) {
            bench.close();
        }
    }

    @Test
    void aFixedDestinationSendsToItsPartnerOnly() throws Exception {
        Studies.Study study = Studies.ct(1, 2);

        assertThat(a.cstore("IU_TO_C", study)).containsOnly(0);

        Bench.await(() -> c.pacs.study(study.uid()).size() == 2, "examen restitué chez C");
        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé de C");
        assertThat(b.pacs.study(study.uid())).as("rien chez B").isEmpty();
    }

    @Test
    void aCtScanOfTheChuIsRoutedToBByItsDicomFields() throws Exception {
        Studies.Study study = Studies.study("CT", "CHU SYNTHETIQUE NORD", 1, 3, 32);

        assertThat(a.cstore("IU_AUTO", study)).containsOnly(0);

        Bench.await(() -> b.pacs.study(study.uid()).size() == 3, "scanner du CHU restitué chez B");
        assertThat(a.sent(study.uid())).singleElement()
                .satisfies(t -> assertThat(t.path("destination").asString()).isEqualTo("box-b"));
        Bench.never(() -> !c.pacs.study(study.uid()).isEmpty(), "scanner reçu par C", Duration.ofSeconds(2));
    }

    @Test
    void anMriIsRoutedToTwoPartnersAtOnce() throws Exception {
        Studies.Study study = Studies.study("MR", "CLINIQUE SYNTHETIQUE", 2, 2, 32);

        assertThat(a.cstore("IU_AUTO", study)).containsOnly(0);

        Bench.await(() -> b.pacs.study(study.uid()).size() == 4 && c.pacs.study(study.uid()).size() == 4,
                "IRM restituée chez B et chez C");
        Bench.await(() -> a.sent(study.uid()).size() == 2 && a.sent(study.uid()).stream()
                .allMatch(t -> "ACKNOWLEDGED".equals(t.path("state").asString())), "deux transferts accusés");
        assertThat(a.sent(study.uid())).extracting(t -> t.path("destination").asString())
                .containsExactlyInAnyOrder("box-b", "box-c");
    }

    @Test
    void anExamMatchingNoRuleIsRefusedAtTheDicomInput() throws Exception {
        // Scanner d'une clinique : ni la règle du CHU, ni celle de l'IRM.
        Studies.Study study = Studies.study("CT", "CLINIQUE SYNTHETIQUE", 1, 2, 32);

        List<Integer> statuses = a.cstore("IU_AUTO", study);

        assertThat(statuses).as("C-STORE refusé, instance par instance").doesNotContain(0);
        Bench.never(() -> a.sentTransfer(study.uid()).isPresent(), "transfert d'un examen sans règle",
                Duration.ofSeconds(3));
    }

    @Test
    void anUndeclaredSenderIsRefusedAtAssociation() {
        Studies.Study study = Studies.ct(1, 1);

        assertThatThrownBy(() -> a.cstore("STATION_INCONNUE", "IU_TO_B", study))
                .as("association refusée : émetteur non autorisé").isInstanceOf(Exception.class);
        Bench.never(() -> a.sentTransfer(study.uid()).isPresent(), "transfert d'un émetteur non déclaré",
                Duration.ofSeconds(2));
    }

    @Test
    void anUnknownDestinationIsRefusedAtAssociation() {
        Studies.Study study = Studies.ct(1, 1);

        assertThatThrownBy(() -> a.cstore("IU_INCONNU", study))
                .as("association refusée : destination inconnue").isInstanceOf(Exception.class);
        Bench.never(() -> a.sentTransfer(study.uid()).isPresent(), "transfert vers une destination inconnue",
                Duration.ofSeconds(2));
    }

    @Test
    void aDestinationWhosePartnerWasNeverPairedFailsWithAClearReason() throws Exception {
        Studies.Study study = Studies.ct(1, 1);

        // IU_TO_D est déclarée, mais aucune box « box-d » n'a été appairée : l'examen est reçu, le transfert échoue.
        assertThat(a.cstore("IU_TO_D", study)).containsOnly(0);

        Bench.await(() -> "FAILED".equals(a.sentState(study.uid())), "transfert vers box-d en échec");
        assertThat(a.sentTransfer(study.uid()).orElseThrow().path("errorMessage").asString())
                .contains("Partenaire non appairé").contains("box-d");
    }
}
