package com.imagunity.scenarios;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;

/** Études DICOM synthétiques : aucun patient réel (patient « SYNTHETIQUE^SCENARIO »), pixels pseudo-aléatoires. */
final class Studies {

    private Studies() {
    }

    /** Étude synthétique prête à être envoyée. */
    record Study(String uid, List<Attributes> instances) {

        List<String> sopInstanceUids() {
            return instances.stream().map(a -> a.getString(Tag.SOPInstanceUID)).toList();
        }
    }

    /** Nouvel UID DICOM unique (racine 2.25, dérivée d'un UUID). */
    static String uid() {
        UUID u = UUID.randomUUID();
        return "2.25." + new java.math.BigInteger(1, java.nio.ByteBuffer.allocate(16)
                .putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array());
    }

    /** Étude CT de {@code series} séries de {@code perSeries} images 64 × 64. */
    static Study ct(int series, int perSeries) {
        return study("CT", "CHU SYNTHETIQUE", series, perSeries, 64);
    }

    /**
     * Étude synthétique.
     *
     * @param modality    modalité (CT, MR, SR…)
     * @param institution nom d'établissement (routage par champ DICOM)
     * @param side        côté des images, en pixels (taille des instances : 2 × side² octets)
     */
    static Study study(String modality, String institution, int series, int perSeries, int side) {
        String studyUid = uid();
        Random random = new Random(studyUid.hashCode());
        List<Attributes> instances = new ArrayList<>();
        for (int s = 1; s <= series; s++) {
            String seriesUid = uid();
            for (int i = 1; i <= perSeries; i++) {
                Attributes a = new Attributes();
                a.setString(Tag.SOPClassUID, VR.UI, "MR".equals(modality) ? UID.MRImageStorage : UID.CTImageStorage);
                a.setString(Tag.SOPInstanceUID, VR.UI, uid());
                a.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
                a.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid);
                a.setInt(Tag.SeriesNumber, VR.IS, s);
                a.setInt(Tag.InstanceNumber, VR.IS, i);
                a.setString(Tag.PatientName, VR.PN, "SYNTHETIQUE^SCENARIO");
                a.setString(Tag.PatientID, VR.LO, "SCENARIO-0001");
                a.setString(Tag.Modality, VR.CS, modality);
                a.setString(Tag.InstitutionName, VR.LO, institution);
                a.setString(Tag.SeriesDescription, VR.LO, "SERIE " + s);
                a.setInt(Tag.Rows, VR.US, side);
                a.setInt(Tag.Columns, VR.US, side);
                a.setInt(Tag.BitsAllocated, VR.US, 16);
                a.setInt(Tag.BitsStored, VR.US, 12);
                a.setInt(Tag.HighBit, VR.US, 11);
                a.setInt(Tag.PixelRepresentation, VR.US, 0);
                a.setInt(Tag.SamplesPerPixel, VR.US, 1);
                a.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
                byte[] pixels = new byte[side * side * 2];
                random.nextBytes(pixels);
                a.setBytes(Tag.PixelData, VR.OW, pixels);
                instances.add(a);
            }
        }
        return new Study(studyUid, List.copyOf(instances));
    }

    /** Pixels de l'instance. */
    static byte[] pixels(Attributes instance) {
        try {
            return instance.getBytes(Tag.PixelData);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Instance au format fichier DICOM (Part 10), comme un PACS la dépose en STOW-RS. */
    static byte[] part10(Attributes instance) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DicomOutputStream out = new DicomOutputStream(bytes, UID.ExplicitVRLittleEndian)) {
            out.writeDataset(instance.createFileMetaInformation(UID.ExplicitVRLittleEndian), instance);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
