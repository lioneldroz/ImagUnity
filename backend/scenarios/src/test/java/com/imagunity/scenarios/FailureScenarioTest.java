package com.imagunity.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scénarios de panne : PACS destinataire arrêté ou qui refuse, box destinataire éteinte, relais arrêté, box émettrice
 * redémarrée. Aucun examen n'est perdu : il arrive dès la fin de la panne, de lui-même ou après une relance depuis la
 * console.
 */
class FailureScenarioTest {

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

    /** Chaque scénario rétablit ce qu'il a cassé, même en échec : les suivants partent d'un banc sain. */
    @AfterEach
    void repair() {
        b.pacs.refuse(false);
        b.pacs.start();
        bench.startRelay();
        b.start();
        a.start();
    }

    @AfterAll
    static void stop() {
        if (bench != null) {
            bench.close();
        }
    }

    @Test
    void whenTheRecipientPacsIsDownTheSenderIsToldAndARetryDeliversTheExam() throws Exception {
        b.pacs.stop();
        Studies.Study study = Studies.ct(2, 2);

        assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);

        // B ne peut pas restituer : son transfert échoue, et l'accusé « en échec » le dit à A.
        Bench.await(() -> "FAILED".equals(b.receivedState(study.uid())), "restitution en échec chez B");
        Bench.await(() -> "FAILED".equals(a.sentState(study.uid())), "accusé d'échec reçu par A");
        assertThat(a.sentTransfer(study.uid()).orElseThrow().path("errorMessage").asString()).isNotBlank();

        // Le PACS revient ; l'opérateur de A relance depuis la console.
        b.pacs.start();
        String id = a.sentTransfer(study.uid()).orElseThrow().path("id").asString();
        assertThat(a.retry(id).statusCode()).isEqualTo(200);

        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé après relance");
        assertThat(b.pacs.study(study.uid())).hasSize(4);
        assertThat(b.receivedState(study.uid())).isEqualTo("RESTORED");
    }

    @Test
    void whenTheRecipientPacsRefusesTheInstancesTheTransferFailsThenSucceedsOnRetry() throws Exception {
        b.pacs.refuse(true);
        Studies.Study study = Studies.ct(1, 3);

        assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);

        Bench.await(() -> "FAILED".equals(a.sentState(study.uid())), "accusé d'échec reçu par A");
        assertThat(b.pacs.study(study.uid())).isEmpty();

        b.pacs.refuse(false);
        assertThat(a.retry(a.sentTransfer(study.uid()).orElseThrow().path("id").asString()).statusCode())
                .isEqualTo(200);

        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé après relance");
        assertThat(b.pacs.study(study.uid())).hasSize(3);
    }

    @Test
    void aRetryOfATransferThatDidNotFailIsRefused() throws Exception {
        Studies.Study study = Studies.ct(1, 1);
        assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);
        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé de B");

        assertThat(a.retry(a.sentTransfer(study.uid()).orElseThrow().path("id").asString()).statusCode())
                .as("seul un transfert en échec se relance").isEqualTo(409);
    }

    @Test
    void anExamSentWhileTheRecipientBoxIsOffIsDeliveredWhenItComesBack() throws Exception {
        b.stop(); // établissement B éteint : la poste restante garde l'examen sur le relais
        Studies.Study study = Studies.ct(2, 3);

        assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);

        Bench.await(() -> "ANNOUNCED".equals(a.sentState(study.uid())), "examen déposé et annoncé");
        Bench.never(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé d'une box éteinte",
                Duration.ofSeconds(3));
        assertThat(b.pacs.study(study.uid())).isEmpty();

        b.start();

        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé après le retour de B");
        assertThat(b.pacs.study(study.uid())).hasSize(6);
    }

    @Test
    void anExamSentWhileTheRelayIsDownLeavesOnceTheRelayIsBack() throws Exception {
        bench.stopRelay();
        Studies.Study study = Studies.ct(1, 2);

        assertThat(a.cstore("IU_TO_B", study)).as("l'entrée DICOM reste ouverte sans relais").containsOnly(0);

        Bench.await(() -> a.sentTransfer(study.uid()).isPresent(), "transfert créé par A");
        Bench.never(() -> !b.pacs.study(study.uid()).isEmpty(), "examen arrivé sans relais", Duration.ofSeconds(3));

        bench.startRelay();

        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé après le retour du relais",
                Duration.ofSeconds(120));
        assertThat(b.pacs.study(study.uid())).hasSize(2);
    }

    @Test
    void theSenderBoxRestartedBeforeTheAckStillGetsIt() throws Exception {
        b.stop();
        Studies.Study study = Studies.ct(1, 2);
        assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);
        Bench.await(() -> "ANNOUNCED".equals(a.sentState(study.uid())), "examen annoncé");

        a.stop(); // la box A redémarre (mise à jour, coupure de courant) avant l'accusé
        a.start();
        b.start();

        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé reçu après le redémarrage de A");
        assertThat(b.pacs.study(study.uid())).hasSize(2);
        assertThat(a.sent(study.uid())).as("pas de doublon").hasSize(1);
    }
}
