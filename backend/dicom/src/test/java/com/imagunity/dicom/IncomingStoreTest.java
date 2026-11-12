package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncomingStoreTest {

    @TempDir
    Path root;

    private IncomingStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new IncomingStore(root);
        store.initialize();
    }

    @Test
    void initializeRemovesInterruptedReceptions() throws IOException {
        Path part = Files.writeString(store.newTempFile(), "incomplet");

        store.initialize();

        assertThat(part).doesNotExist();
    }

    @Test
    void finalPathCannotEscapeTheRoot() {
        assertThatThrownBy(() -> store.finalPath("..", "..", "..", "..", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recoversInstancesWithTheirMetadata() throws IOException {
        Path file = store.finalPath("IU_TO_B", "box-b", "2.25.1", "2.25.1.1", "2.25.1.1.1");
        writePart10(file, "2.25.1.1.1");

        var recovered = store.recover();

        assertThat(recovered).singleElement().satisfies(r -> {
            assertThat(r.calledAet()).isEqualTo("IU_TO_B");
            assertThat(r.destination()).isEqualTo("box-b");
            assertThat(r.studyInstanceUid()).isEqualTo("2.25.1");
            assertThat(r.instance().seriesInstanceUid()).isEqualTo("2.25.1.1");
            assertThat(r.instance().sopInstanceUid()).isEqualTo("2.25.1.1.1");
            assertThat(r.instance().sopClassUid()).isEqualTo(UID.CTImageStorage);
            assertThat(r.instance().transferSyntaxUid()).isEqualTo(UID.ExplicitVRLittleEndian);
        });
    }

    @Test
    void recoversInstancesStoredByEarlierVersionsWithoutPartner() throws IOException {
        Path legacy = root.resolve("IU_TO_B").resolve("2.25.1").resolve("2.25.1.1").resolve("2.25.1.1.1.dcm");
        writePart10(legacy, "2.25.1.1.1");

        assertThat(store.recover()).singleElement().satisfies(r -> {
            assertThat(r.destination()).isNull();
            assertThat(r.studyInstanceUid()).isEqualTo("2.25.1");
            assertThat(r.instance().seriesInstanceUid()).isEqualTo("2.25.1.1");
        });
    }

    @Test
    void copiesAnInstanceForAnotherPartner() throws IOException {
        Path source = store.finalPath("IU_AUTO", "box-b", "2.25.1", "2.25.1.1", "2.25.1.1.1");
        writePart10(source, "2.25.1.1.1");
        Path copy = store.finalPath("IU_AUTO", "box-c", "2.25.1", "2.25.1.1", "2.25.1.1.1");

        store.copy(source, copy);
        Files.delete(source);

        assertThat(copy).exists();
        assertThat(store.recover()).singleElement().satisfies(r -> assertThat(r.destination()).isEqualTo("box-c"));
    }

    @Test
    void recoveryIgnoresUnexpectedFiles() throws IOException {
        Files.writeString(root.resolve("racine.dcm"), "x");                       // mauvaise profondeur
        Files.createDirectories(root.resolve("IU_TO_B"));
        Files.writeString(root.resolve("IU_TO_B").resolve("niveau1.dcm"), "x");  // mauvaise profondeur
        Path notes = root.resolve("IU_TO_B").resolve("2.25.1").resolve("2.25.1.1");
        Files.createDirectories(notes);
        Files.writeString(notes.resolve("notes.txt"), "x");                        // pas un .dcm
        Path temp = root.resolve(".tmp").resolve("a").resolve("b");
        Files.createDirectories(temp);
        Files.writeString(temp.resolve("c.dcm"), "x");                            // sous .tmp

        assertThat(store.recover()).isEmpty();
    }

    @Test
    void recoveryIgnoresUnreadableInstances() throws IOException {
        Path garbage = store.finalPath("IU_TO_B", "box-b", "2.25.1", "2.25.1.1", "2.25.1.1.1");
        Files.createDirectories(garbage.getParent());
        Files.write(garbage, new byte[] {1, 2, 3});

        Path noMeta = store.finalPath("IU_TO_B", "box-b", "2.25.1", "2.25.1.1", "2.25.1.1.2");
        try (DicomOutputStream out = new DicomOutputStream(Files.newOutputStream(noMeta), UID.ExplicitVRLittleEndian)) {
            out.writeDataset(null, TestDatasets.ct("2.25.1", "2.25.1.1", "2.25.1.1.2"));
        }

        assertThat(store.recover()).isEmpty();
    }

    private static void writePart10(Path file, String sopInstanceUid) throws IOException {
        Files.createDirectories(file.getParent());
        var dataset = TestDatasets.ct("2.25.1", "2.25.1.1", sopInstanceUid);
        var fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
        try (DicomOutputStream out = new DicomOutputStream(file.toFile())) {
            out.writeDataset(fmi, dataset);
        }
    }
}
