package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.exam.StudyRestorer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Configuration DICOM centrale : entrée (sources, AET appelés, routes) et restitution vers plusieurs PACS. */
class CentralInputTest {

    @TempDir
    Path dir;

    private final List<DicomStoreScp> started = new java.util.ArrayList<>();

    @AfterEach
    void stop() {
        started.forEach(DicomStoreScp::stop);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private DicomStoreScp scp(int port, Set<String> calling, Map<String, String> routes, Path storage,
                              BlockingQueue<ReceivedStudy> sink) throws IOException {
        DicomStoreScp scp = new DicomStoreScp(new DicomInputConfig("127.0.0.1", port, calling, routes, storage,
                Duration.ofMillis(300), 0), sink::add);
        TestPorts.start(scp);
        started.add(scp);
        return scp;
    }

    /** Entrée centrale simple : source CT_URG (depuis 127.0.0.1), AET IMAGUNITY_NORD, CT → box-b, sinon refus. */
    private static final class Input implements CentralInput {
        volatile boolean paired = true;

        @Override
        public boolean callingAllowed(String callingAet, InetAddress from) {
            return "CT_URG".equals(callingAet) && from.isLoopbackAddress();
        }

        @Override
        public boolean calledKnown(String calledAet) {
            return "IMAGUNITY_NORD".equals(calledAet);
        }

        @Override
        public List<String> destinations(String source, String calledAet, Attributes dataset) {
            if (!paired) {
                throw new IllegalArgumentException("box B pas encore appairée");
            }
            if (!"CT".equals(dataset.getString(Tag.Modality))) {
                throw new IllegalArgumentException("Aucune route sortante ne correspond");
            }
            return List.of("box-b");
        }

        @Override
        public String describe() {
            return "essai";
        }
    }

    @Test
    void theCentralInputReplacesSourcesCalledAetsAndRoutesWithoutRestart() throws Exception {
        int port = freePort();
        BlockingQueue<ReceivedStudy> studies = new LinkedBlockingQueue<>();
        DicomStoreScp scp = new DicomStoreScp(new DicomInputConfig("127.0.0.1", port, Set.of("ORTHANC_A"),
                Map.of("IU_TO_B", "box-b"), dir.resolve("entree"), Duration.ofMillis(300), 0), studies::add);
        AtomicReference<Optional<CentralInput>> central = new AtomicReference<>(Optional.empty());
        scp.central(central::get);
        TestPorts.start(scp);
        started.add(scp);

        // Sans configuration centrale : la configuration locale.
        try (TestScu local = new TestScu("ORTHANC_A", port)) {
            assertThat(local.echo("IU_TO_B")).isEqualTo(Status.Success);
        }
        Input input = new Input();
        central.set(Optional.of(input));
        try (TestScu old = new TestScu("ORTHANC_A", port)) {
            assertThatThrownBy(() -> old.echo("IMAGUNITY_NORD")).isInstanceOf(AAssociateRJ.class)
                    .satisfies(e -> assertThat(((AAssociateRJ) e).getReason())
                            .isEqualTo(AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED));
        }
        try (TestScu ct = new TestScu("CT_URG", port)) {
            assertThatThrownBy(() -> ct.echo("IU_TO_B")).isInstanceOf(AAssociateRJ.class)
                    .satisfies(e -> assertThat(((AAssociateRJ) e).getReason())
                            .isEqualTo(AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED));
            Attributes mr = TestDatasets.ct("2.25.71", "2.25.71.1", "2.25.71.1.1");
            mr.setString(Tag.Modality, VR.CS, "MR");
            assertThat(ct.store("IMAGUNITY_NORD", List.of(mr))).as("aucune route").containsOnly(
                    Status.ProcessingFailure);
            input.paired = false;
            assertThat(ct.store("IMAGUNITY_NORD", List.of(TestDatasets.ct("2.25.72", "2.25.72.1", "2.25.72.1.1"))))
                    .as("box pas encore appairée").containsOnly(Status.ProcessingFailure);
            input.paired = true;
            assertThat(ct.store("IMAGUNITY_NORD", List.of(TestDatasets.ct("2.25.73", "2.25.73.1", "2.25.73.1.1"))))
                    .containsOnly(Status.Success);
        }
        ReceivedStudy study = studies.poll(5, TimeUnit.SECONDS);
        assertThat(study).isNotNull();
        assertThat(study.destination()).isEqualTo("box-b");
        assertThat(study.calledAet()).isEqualTo("IMAGUNITY_NORD");
        assertThat(study.callingAets()).containsExactly("CT_URG");

        // STOW-RS : même AET appelé et mêmes routes.
        Attributes stowed = TestDatasets.ct("2.25.74", "2.25.74.1", "2.25.74.1.1");
        Path file = dir.resolve("stow.dcm");
        try (DicomOutputStream out = new DicomOutputStream(file.toFile())) {
            out.writeDataset(stowed.createFileMetaInformation(UID.ExplicitVRLittleEndian), stowed);
        }
        try (var in = Files.newInputStream(file)) {
            assertThatThrownBy(() -> scp.storePart10("dicomweb:pacs", "IU_TO_B", in, null))
                    .isInstanceOf(DicomStoreScp.Refused.class).hasMessageContaining("Destination inconnue");
        }
        try (var in = Files.newInputStream(file)) {
            assertThat(scp.storePart10("dicomweb:pacs", "IMAGUNITY_NORD", in, null)).isNotNull();
        }
    }

    /** Instance Part 10 à restituer. */
    private StudyRestorer.Instance instance(String study, String series, String sop, String modality)
            throws IOException {
        Attributes ds = TestDatasets.ct(study, series, sop);
        ds.setString(Tag.Modality, VR.CS, modality);
        Path file = Files.createDirectories(dir.resolve("restitution").resolve(series)).resolve(sop + ".dcm");
        try (DicomOutputStream out = new DicomOutputStream(file.toFile())) {
            out.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds);
        }
        return new StudyRestorer.Instance(series, sop, file);
    }

    @Test
    void incomingRoutesRestoreToSeveralPacs() throws Exception {
        int nordPort = freePort();
        int irmPort = freePort();
        BlockingQueue<ReceivedStudy> nord = new LinkedBlockingQueue<>();
        BlockingQueue<ReceivedStudy> irm = new LinkedBlockingQueue<>();
        scp(nordPort, Set.of("IMAGUNITY_NORD"), Map.of("PACS_NORD", "pacs"), dir.resolve("nord"), nord);
        scp(irmPort, Set.of("IMAGUNITY_NORD"), Map.of("PACS_IRM", "pacs"), dir.resolve("irm"), irm);
        RestoreConfig toNord = new RestoreConfig("127.0.0.1", nordPort, "PACS_NORD", "IMAGUNITY_NORD");
        RestoreConfig toIrm = new RestoreConfig("127.0.0.1", irmPort, "PACS_IRM", "IMAGUNITY_NORD");
        UUID boxB = UUID.randomUUID();
        DicomRestorer restorer = new DicomRestorer(new RestoreConfig("127.0.0.1", freePort(), "LOCAL", "LOCAL"));
        AtomicReference<Optional<DicomRestorer.Router>> router = new AtomicReference<>(Optional.empty());
        restorer.router(router::get);
        router.set(Optional.of((origin, ds) -> {
            if (!boxB.equals(origin)) {
                throw new DicomValidationException("Aucune route entrante ne correspond");
            }
            // IRM vers le PACS IRM, tout le reste aux deux PACS.
            return "MR".equals(ds.getString(Tag.Modality)) ? List.of(toIrm) : List.of(toNord, toIrm);
        }));

        String study = "2.25.80";
        List<StudyRestorer.Instance> instances = List.of(instance(study, study + ".1", study + ".1.1", "CT"),
                instance(study, study + ".2", study + ".2.1", "MR"));
        restorer.restore(boxB, study, instances);
        ReceivedStudy atNord = nord.poll(5, TimeUnit.SECONDS);
        ReceivedStudy atIrm = irm.poll(5, TimeUnit.SECONDS);
        assertThat(atNord.instances()).extracting(i -> i.sopInstanceUid()).containsExactly(study + ".1.1");
        assertThat(atIrm.instances()).extracting(i -> i.sopInstanceUid())
                .containsExactlyInAnyOrder(study + ".1.1", study + ".2.1");
        assertThat(atNord.callingAets()).containsExactly("IMAGUNITY_NORD");

        // Émetteur sans route : rien n'est restitué, avec la raison.
        assertThatThrownBy(() -> restorer.restore(UUID.randomUUID(), study, instances))
                .isInstanceOf(DicomValidationException.class).hasMessageContaining("Aucune route entrante");
        assertThatThrownBy(() -> restorer.restore(study, instances)).as("émetteur inconnu")
                .isInstanceOf(DicomValidationException.class);
        router.set(Optional.of((origin, ds) -> List.of()));
        assertThatThrownBy(() -> restorer.restore(boxB, study, instances))
                .hasMessageContaining("Aucune destination de restitution");
        assertThat(((DicomRestorer.Router) (origin, ds) -> List.of()).describe(toNord))
                .isEqualTo("PACS_NORD (127.0.0.1:" + nordPort + ")");
        assertThat(nord.poll(500, TimeUnit.MILLISECONDS)).as("rien de plus").isNull();
    }
}
