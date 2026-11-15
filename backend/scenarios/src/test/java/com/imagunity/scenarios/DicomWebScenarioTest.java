package com.imagunity.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import org.dcm4che3.data.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scénarios DICOMweb : le PACS de A dépose l'examen par HTTP (STOW-RS) au lieu du C-STORE ; il arrive de la même
 * façon dans le PACS de B. Les dépôts mal authentifiés ou mal adressés sont refusés et rien ne part.
 */
class DicomWebScenarioTest {

    private static Bench bench;
    private static Box a;
    private static Box b;

    @BeforeAll
    static void start() {
        bench = new Bench();
        a = bench.box(Box.Spec.of("box-a").route("IU_TO_B", "box-b"));
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

    @Test
    void anExamDepositedByStowRsReachesThePacsOfTheOtherSite() {
        Studies.Study study = Studies.ct(2, 3);

        HttpResponse<String> response = a.stow("IU_TO_B", study);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/dicom+json");
        study.sopInstanceUids().forEach(uid -> assertThat(response.body()).contains(uid));
        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé de B reçu par A");
        assertThat(b.pacs.study(study.uid())).hasSize(6);
        for (var original : study.instances()) {
            assertThat(Studies.pixels(b.pacs.instance(original.getString(Tag.SOPInstanceUID))))
                    .isEqualTo(Studies.pixels(original));
        }
    }

    @Test
    void stowRsToAStudyUrlRefusesInstancesOfAnotherStudy() {
        Studies.Study study = Studies.ct(1, 2);
        String otherStudy = Studies.uid();

        // POST /dicomweb/IU_TO_B/studies/<UID d'une autre étude> : chaque instance est refusée, rien ne part.
        HttpResponse<String> response = a.stowRaw("/dicomweb/IU_TO_B/studies/" + otherStudy, Box.MULTIPART,
                Box.multipart(study), Bench.DICOMWEB_USER + ":" + Bench.DICOMWEB_PASSWORD);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        Bench.never(() -> a.sentTransfer(study.uid()).isPresent() || a.sentTransfer(otherStudy).isPresent(),
                "transfert d'une étude refusée", Duration.ofSeconds(3));
    }

    @Test
    void stowRsToTheRightStudyUrlIsAccepted() {
        Studies.Study study = Studies.ct(1, 2);

        HttpResponse<String> response = a.stowRaw("/dicomweb/IU_TO_B/studies/" + study.uid(), Box.MULTIPART,
                Box.multipart(study), Bench.DICOMWEB_USER + ":" + Bench.DICOMWEB_PASSWORD);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Bench.await(() -> b.pacs.study(study.uid()).size() == 2, "examen restitué dans le PACS de B");
    }

    @Test
    void aDepositWithAWrongPasswordIsRefusedAndNothingIsSent() {
        Studies.Study study = Studies.ct(1, 1);

        assertThat(a.stow("IU_TO_B", study, Bench.DICOMWEB_USER + ":mauvais").statusCode()).isEqualTo(401);
        assertThat(a.stow("IU_TO_B", study, null).statusCode()).as("sans compte").isEqualTo(401);

        Bench.never(() -> a.sentTransfer(study.uid()).isPresent(), "transfert d'un dépôt refusé",
                Duration.ofSeconds(3));
    }

    @Test
    void theOperatorAccountCannotDepositExams() {
        Studies.Study study = Studies.ct(1, 1);

        // Comptes séparés : le compte de la console n'a pas le rôle DICOMweb.
        assertThat(a.stow("IU_TO_B", study, Bench.CONSOLE_USER + ":" + Bench.CONSOLE_PASSWORD).statusCode())
                .isEqualTo(403);
    }

    @Test
    void aDepositToAnUnknownDestinationIsRefused() {
        Studies.Study study = Studies.ct(1, 2);

        HttpResponse<String> response = a.stow("IU_INCONNU", study);

        assertThat(response.statusCode()).as("toutes les instances refusées").isEqualTo(409);
        Bench.never(() -> a.sentTransfer(study.uid()).isPresent(), "transfert vers une destination inconnue",
                Duration.ofSeconds(3));
    }

    @Test
    void aDepositThatIsNotDicomIsRefused() {
        HttpResponse<String> response = a.stowRaw("/dicomweb/IU_TO_B/studies", "application/json", "{}".getBytes(),
                Bench.DICOMWEB_USER + ":" + Bench.DICOMWEB_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    void aTruncatedMultipartBodyIsRefused() {
        byte[] full = Box.multipart(Studies.ct(1, 1));
        byte[] truncated = java.util.Arrays.copyOf(full, 200); // coupé au milieu de la première instance

        HttpResponse<String> response = a.stowRaw("/dicomweb/IU_TO_B/studies", Box.MULTIPART, truncated,
                Bench.DICOMWEB_USER + ":" + Bench.DICOMWEB_PASSWORD);

        assertThat(response.statusCode()).as(response.body()).isBetween(400, 499);
    }
}
