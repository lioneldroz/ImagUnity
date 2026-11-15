package com.imagunity.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scénarios nominaux : un examen envoyé en DICOM (C-STORE) par le PACS de l'établissement A arrive, identique, dans le
 * PACS de l'établissement B, en passant par la box A, le relais et la box B.
 */
class DicomTransferScenarioTest {

    private static Bench bench;
    private static Box a;
    private static Box b;

    @BeforeAll
    static void start() {
        bench = new Bench();
        // Paquets de 100 Ko au plus : un examen de plusieurs séries part en plusieurs paquets.
        a = bench.box(Box.Spec.of("box-a").route("IU_TO_B", "box-b").maxPartSize("100KB"));
        b = bench.box(Box.Spec.of("box-b"));
        bench.enroll(a);
        bench.enroll(b);
        bench.pair(a, b, "box-b");
    }

    @AfterAll
    static void stop() {
        if (bench != null) {
            bench.close();
        }
    }

    /** Chaque instance reçue par le PACS B est celle envoyée par le PACS A : mêmes attributs, mêmes pixels. */
    private static void assertSameStudy(Studies.Study sent) {
        List<Attributes> received = b.pacs.study(sent.uid());
        assertThat(received).hasSize(sent.instances().size());
        for (Attributes original : sent.instances()) {
            Attributes copy = b.pacs.instance(original.getString(Tag.SOPInstanceUID));
            assertThat(copy).as("instance %s reçue", original.getString(Tag.SOPInstanceUID)).isNotNull();
            assertThat(copy.getString(Tag.SeriesInstanceUID)).isEqualTo(original.getString(Tag.SeriesInstanceUID));
            assertThat(copy.getString(Tag.PatientName)).isEqualTo(original.getString(Tag.PatientName));
            assertThat(Studies.pixels(copy)).as("pixels intacts").isEqualTo(Studies.pixels(original));
        }
    }

    @Test
    void anExamSentByCStoreReachesThePacsOfTheOtherSiteUnchanged() throws Exception {
        Studies.Study study = Studies.ct(3, 4);

        assertThat(a.cstore("IU_TO_B", study)).as("statuts C-STORE").containsOnly(0);

        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé de B reçu par A");
        assertSameStudy(study);
        assertThat(b.receivedState(study.uid())).isEqualTo("RESTORED");
        assertThat(b.receivedTransfer(study.uid()).orElseThrow().path("restoredInstances").asInt()).isEqualTo(12);
        assertThat(a.pacs.study(study.uid())).as("rien ne revient au PACS émetteur").isEmpty();
    }

    @Test
    void aLargeExamIsSplitIntoSeveralPacketsAndRestoredInFull() throws Exception {
        // 4 séries de 5 images de 128 × 128 (32 Ko) : plus de 100 Ko par série, donc plusieurs paquets.
        Studies.Study study = Studies.study("CT", "CHU SYNTHETIQUE", 4, 5, 128);

        assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);

        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé de B reçu par A");
        String transferId = a.sentTransfer(study.uid()).orElseThrow().path("id").asString();
        assertThat(a.parts(transferId)).as("examen découpé en plusieurs paquets").hasSizeGreaterThan(1)
                .allSatisfy(p -> assertThat(p.path("uploaded").asBoolean()).isTrue());
        assertSameStudy(study);
    }

    @Test
    void severalExamsSentOneAfterTheOtherAllArrive() throws Exception {
        List<Studies.Study> studies = List.of(Studies.ct(1, 2), Studies.ct(2, 2), Studies.ct(1, 3));

        for (Studies.Study study : studies) {
            assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);
        }

        for (Studies.Study study : studies) {
            Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé de " + study.uid());
            assertSameStudy(study);
        }
    }

    @Test
    void theBoxAnswersTheVerificationOfItsPacs() throws Exception {
        try (PacsClient pacs = new PacsClient(a.pacsAet(), a.dicomPort)) {
            assertThat(pacs.echo("IU_TO_B")).as("C-ECHO").isZero();
        }
    }
}
