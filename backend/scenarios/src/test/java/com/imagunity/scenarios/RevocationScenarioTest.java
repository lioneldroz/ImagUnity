package com.imagunity.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Scénarios d'annuaire : révocation d'une box par le responsable, expiration puis renouvellement d'une entrée. Une box
 * révoquée ou expirée ne reçoit plus rien et n'envoie plus rien ; aucun examen n'atteint le PACS d'en face.
 *
 * <p>Chaque scénario part d'une organisation neuve : une révocation est définitive.
 */
class RevocationScenarioTest {

    private Bench bench;
    private Box a;
    private Box b;

    private void start(Duration validityOfB) {
        bench = new Bench();
        a = bench.box(Box.Spec.of("box-a").route("IU_TO_B", "box-b"));
        b = bench.box(Box.Spec.of("box-b"));
        bench.enroll(a);
        bench.enroll(b, validityOfB);
        bench.pair(a, b, "box-b");
    }

    @BeforeEach
    void noBench() {
        bench = null;
    }

    @AfterEach
    void stop() {
        if (bench != null) {
            bench.close();
        }
    }

    /** Envoie un examen et attend qu'il arrive (le banc fonctionne avant la panne d'annuaire simulée). */
    private void sendSuccessfully() throws Exception {
        Studies.Study study = Studies.ct(1, 2);
        assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);
        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "premier examen accusé");
        assertThat(b.pacs.study(study.uid())).hasSize(2);
    }

    private static void awaitStatus(Box observer, UUID box, String status) {
        Bench.await(() -> observer.directoryView(box).map(v -> status.equals(v.path("status").asString()))
                .orElse(false), observer.spec.name() + " voit " + box + " " + status);
    }

    @Test
    void aRevokedRecipientReceivesNothingMore() throws Exception {
        start(Duration.ofDays(30));
        sendSuccessfully();

        bench.revoke(b, "box volée (scénario)");
        awaitStatus(a, b.boxId(), "REVOKED");
        Studies.Study study = Studies.ct(1, 2);

        assertThat(a.cstore("IU_TO_B", study)).as("l'entrée DICOM accepte l'examen").containsOnly(0);

        Bench.await(() -> "FAILED".equals(a.sentState(study.uid())), "transfert vers une box révoquée en échec");
        assertThat(a.sentTransfer(study.uid()).orElseThrow().path("errorMessage").asString())
                .contains("révoquée");
        Bench.never(() -> !b.pacs.study(study.uid()).isEmpty(), "examen reçu par une box révoquée",
                Duration.ofSeconds(3));
        String transferId = a.sentTransfer(study.uid()).orElseThrow().path("id").asString();
        assertThat(a.retry(transferId).statusCode()).as("relance acceptée par la console").isEqualTo(200);
        Bench.await(() -> "FAILED".equals(a.sentState(study.uid())), "relance refusée de nouveau");
        assertThat(b.pacs.study(study.uid())).as("toujours rien chez B").isEmpty();
    }

    @Test
    void aRevokedSenderCanNoLongerDeliverAnything() throws Exception {
        start(Duration.ofDays(30));
        sendSuccessfully();

        bench.revoke(a, "box compromise (scénario)");
        awaitStatus(b, a.boxId(), "REVOKED");
        awaitStatus(a, a.boxId(), "REVOKED");
        Bench.sleep(Duration.ofSeconds(3)); // le relais relit l'annuaire chaque seconde
        Studies.Study study = Studies.ct(1, 2);

        // La box révoquée reçoit encore de son PACS, mais le relais refuse ses dépôts : son transfert échoue.
        assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);

        Bench.await(() -> "FAILED".equals(a.sentState(study.uid())), "dépôt de la box révoquée refusé");
        assertThat(a.sentTransfer(study.uid()).orElseThrow().path("errorMessage").asString())
                .as("refus du relais").contains("REVOKED");
        Bench.never(() -> !b.pacs.study(study.uid()).isEmpty(), "examen d'une box révoquée restitué",
                Duration.ofSeconds(3));
        assertThat(b.receivedState(study.uid())).as("rien n'est restitué").isNotEqualTo("RESTORED");
    }

    @Test
    void anExpiredRecipientIsRefusedUntilItsEntryIsRenewed() throws Exception {
        start(Duration.ofSeconds(40)); // entrée de B valable encore 40 s
        sendSuccessfully();

        awaitStatus(a, b.boxId(), "EXPIRED");
        Studies.Study study = Studies.ct(1, 2);
        assertThat(a.cstore("IU_TO_B", study)).containsOnly(0);

        Bench.await(() -> "FAILED".equals(a.sentState(study.uid())), "transfert vers une entrée expirée en échec");
        assertThat(a.sentTransfer(study.uid()).orElseThrow().path("errorMessage").asString()).contains("expirée");
        assertThat(b.pacs.study(study.uid())).isEmpty();

        // Le responsable renouvelle l'entrée ; l'opérateur relance.
        bench.renew(b, Duration.ofDays(30));
        awaitStatus(a, b.boxId(), "ACTIVE");
        awaitStatus(b, b.boxId(), "ACTIVE");
        Bench.sleep(Duration.ofSeconds(3)); // relecture de l'annuaire par le relais
        assertThat(a.retry(a.sentTransfer(study.uid()).orElseThrow().path("id").asString()).statusCode())
                .isEqualTo(200);

        Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé après renouvellement");
        assertThat(b.pacs.study(study.uid())).hasSize(2);
    }
}
