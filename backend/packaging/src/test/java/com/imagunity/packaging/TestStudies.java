package com.imagunity.packaging;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Études de test. L'empaquetage ne dépend pas du contenu DICOM : les « instances » sont des
 * fichiers d'octets synthétiques (aucune donnée de patient).
 */
final class TestStudies {

    static final String STUDY = "2.25.7";

    private TestStudies() {
    }

    /** Instance de {@code size} octets ; {@code compressible} : contenu répétitif (comme une image peu bruitée). */
    static ReceivedInstance instance(Path dir, String series, String sop, int size, boolean compressible)
            throws IOException {
        byte[] data = new byte[size];
        if (compressible) {
            for (int i = 0; i < size; i++) {
                data[i] = (byte) (i / 64);
            }
        } else {
            new Random(sop.hashCode()).nextBytes(data);
        }
        Path file = Files.createDirectories(dir.resolve(series)).resolve(sop + ".dcm");
        Files.write(file, data);
        return new ReceivedInstance("1.2.840.10008.5.1.4.1.1.2", sop, series, "1.2.840.10008.1.2.1", file, size);
    }

    static ReceivedStudy study(List<ReceivedInstance> instances) {
        Instant now = Instant.parse("2026-11-11T10:00:00Z");
        return new ReceivedStudy(STUDY, "IU_TO_B", "box-b", Set.of("ORTHANC_A"), instances, now, now);
    }

    /** {@code seriesCount} séries de {@code perSeries} instances de {@code size} octets. */
    static ReceivedStudy study(Path dir, int seriesCount, int perSeries, int size, boolean compressible)
            throws IOException {
        List<ReceivedInstance> instances = new ArrayList<>();
        for (int s = 1; s <= seriesCount; s++) {
            for (int i = 1; i <= perSeries; i++) {
                instances.add(instance(dir, STUDY + "." + s, STUDY + "." + s + "." + i, size, compressible));
            }
        }
        return study(instances);
    }
}
