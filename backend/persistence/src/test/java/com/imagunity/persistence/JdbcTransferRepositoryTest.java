package com.imagunity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferState;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcTransferRepositoryTest {

    private final JdbcTransferRepository repository = new JdbcTransferRepository(TestDatabase.migrated());
    private final Instant now = Instant.parse("2026-11-11T12:00:00.123456Z");

    private Transfer received(String study) {
        return Transfer.received(study, "IU_TO_B", "box-b", 1234, now);
    }

    private static TransferPart part(UUID transferId, int index, int count) {
        return new TransferPart(transferId, index, count, Path.of("outbox", index + ".tar.zst"), 100L * index,
                "a".repeat(64), null, false);
    }

    @Test
    void createsAndReadsTransfer() {
        Transfer transfer = received("2.25.1");

        repository.create(transfer);

        assertThat(repository.find(transfer.id())).contains(transfer);
        assertThat(repository.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void updatesStateAndPackaging() {
        Transfer transfer = received("2.25.1");
        repository.create(transfer);
        Instant later = now.plus(5, ChronoUnit.SECONDS);

        Transfer packaged = transfer.packaged("groupe-1", 2, later);
        repository.update(packaged);

        Transfer read = repository.find(transfer.id()).orElseThrow();
        assertThat(read.state()).isEqualTo(TransferState.PACKAGED);
        assertThat(read.packageGroupId()).isEqualTo("groupe-1");
        assertThat(read.partCount()).isEqualTo(2);
        assertThat(read.updatedAt()).isEqualTo(later);
        assertThat(read.createdAt()).isEqualTo(now);
    }

    @Test
    void keepsErrorMessage() {
        Transfer transfer = received("2.25.1");
        repository.create(transfer);

        repository.update(transfer.failed("partenaire inconnu", now));

        assertThat(repository.find(transfer.id()).orElseThrow().errorMessage()).isEqualTo("partenaire inconnu");
    }

    @Test
    void updatingUnknownTransferFails() {
        assertThatThrownBy(() -> repository.update(received("2.25.1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findsActiveTransferOnly() {
        Transfer done = received("2.25.1").failed("ancien échec", now);
        repository.create(done);
        assertThat(repository.findActive("2.25.1", "IU_TO_B", "box-b")).isEmpty();

        Transfer active = received("2.25.1");
        repository.create(active);
        // Même étude, même destination DICOM (routage par règles), autre partenaire : transfert distinct.
        Transfer toC = Transfer.received("2.25.1", "IU_TO_B", "box-c", 1234, now);
        repository.create(toC);

        assertThat(repository.findActive("2.25.1", "IU_TO_B", "box-b")).map(Transfer::id).contains(active.id());
        assertThat(repository.findActive("2.25.1", "IU_TO_B", "box-c")).map(Transfer::id).contains(toC.id());
        assertThat(repository.findActive("2.25.1", "IU_TO_C", "box-b")).isEmpty();
        assertThat(repository.findActive("2.25.9", "IU_TO_B", "box-b")).isEmpty();
    }

    @Test
    void keepsThePackagedInstancesOfATransfer() {
        Transfer transfer = received("2.25.1");
        repository.create(transfer);
        assertThat(repository.instances(transfer.id())).as("transfert d'une version antérieure").isEmpty();

        repository.saveInstances(transfer.id(), java.util.Set.of("2.25.1.1.1", "2.25.1.1.2"));
        assertThat(repository.instances(transfer.id())).containsExactlyInAnyOrder("2.25.1.1.1", "2.25.1.1.2");
        repository.saveInstances(transfer.id(), java.util.Set.of("2.25.1.1.3"));
        assertThat(repository.instances(transfer.id())).containsExactly("2.25.1.1.3");

        Transfer late = received("2.25.1");
        repository.create(late);
        assertThat(repository.findActive("2.25.1", "IU_TO_B", "box-b")).map(Transfer::id)
                .containsExactlyInAnyOrder(transfer.id(), late.id());
    }

    @Test
    void findsByStates() {
        Transfer a = received("2.25.1");
        Transfer b = received("2.25.2").packaged("g", 1, now);
        Transfer c = received("2.25.3").failed("x", now);
        repository.create(a);
        repository.create(b);
        repository.create(c);

        assertThat(repository.findByStates(Set.of(TransferState.RECEIVED, TransferState.PACKAGED)))
                .extracting(Transfer::id).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(repository.findByStates(Set.of())).isEmpty();
    }

    @Test
    void savesAndUpdatesParts() {
        Transfer transfer = received("2.25.1");
        repository.create(transfer);
        repository.saveParts(transfer.id(), List.of(part(transfer.id(), 2, 2), part(transfer.id(), 1, 2)));

        List<TransferPart> parts = repository.parts(transfer.id());
        assertThat(parts).extracting(TransferPart::index).containsExactly(1, 2);
        assertThat(parts.getFirst().transportFileId()).isNull();
        assertThat(parts.getFirst().file()).isEqualTo(Path.of("outbox", "1.tar.zst"));

        repository.updatePart(parts.getFirst().handedOver(42));
        assertThat(repository.findPartByTransportFileId(42)).map(TransferPart::index).contains(1);

        repository.updatePart(repository.findPartByTransportFileId(42).orElseThrow().markUploaded());
        assertThat(repository.parts(transfer.id()).getFirst().uploaded()).isTrue();
        assertThat(repository.findPartByTransportFileId(43)).isEmpty();

        repository.updatePart(parts.get(1).sealedAs(Path.of("outbox", "2.tar.zst.iu1"), 99, "b".repeat(64)));
        TransferPart sealed = repository.parts(transfer.id()).get(1);
        assertThat(sealed.file()).isEqualTo(Path.of("outbox", "2.tar.zst.iu1"));
        assertThat(sealed.size()).isEqualTo(99);
        assertThat(sealed.sha256()).isEqualTo("b".repeat(64));
    }

    @Test
    void savingPartsReplacesPreviousOnes() {
        Transfer transfer = received("2.25.1");
        repository.create(transfer);
        repository.saveParts(transfer.id(), List.of(part(transfer.id(), 1, 2), part(transfer.id(), 2, 2)));

        repository.saveParts(transfer.id(), List.of(part(transfer.id(), 1, 1)));

        assertThat(repository.parts(transfer.id())).hasSize(1);
    }

    @Test
    void updatingUnknownPartFails() {
        assertThatThrownBy(() -> repository.updatePart(part(UUID.randomUUID(), 1, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listsRecentTransfersAndCountsThemByState() {
        Transfer a = received("2.25.1");
        Transfer b = received("2.25.2");
        Transfer c = received("2.25.3");
        repository.create(a);
        repository.create(b);
        repository.create(c);
        repository.update(b.failed("x", now.plusSeconds(10)));
        repository.update(c.packaged("g", 1, now.plusSeconds(5)));

        assertThat(repository.findRecent(2)).extracting(Transfer::id).containsExactly(b.id(), c.id());
        assertThat(repository.findRecent(10)).hasSize(3);
        assertThat(repository.countByState()).containsOnly(
                java.util.Map.entry(TransferState.RECEIVED, 1L),
                java.util.Map.entry(TransferState.PACKAGED, 1L),
                java.util.Map.entry(TransferState.FAILED, 1L));
    }

    @Test
    void emptyDatabaseHasNoCounts() {
        assertThat(repository.countByState()).isEmpty();
        assertThat(repository.findRecent(5)).isEmpty();
    }
}
