package com.imagunity.dicom;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;

/** Jeux de données DICOM synthétiques : aucun patient réel. */
final class TestDatasets {

    private TestDatasets() {
    }

    /** Image CT minimale de 16 × 16 pixels. */
    static Attributes ct(String studyUid, String seriesUid, String sopInstanceUid) {
        Attributes attrs = new Attributes();
        attrs.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
        attrs.setString(Tag.SOPInstanceUID, VR.UI, sopInstanceUid);
        attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        attrs.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid);
        attrs.setString(Tag.PatientName, VR.PN, "Test^Synthetique");
        attrs.setString(Tag.PatientID, VR.LO, "TEST-0001");
        attrs.setString(Tag.Modality, VR.CS, "CT");
        attrs.setInt(Tag.Rows, VR.US, 16);
        attrs.setInt(Tag.Columns, VR.US, 16);
        attrs.setInt(Tag.BitsAllocated, VR.US, 16);
        attrs.setInt(Tag.BitsStored, VR.US, 12);
        attrs.setInt(Tag.HighBit, VR.US, 11);
        attrs.setInt(Tag.PixelRepresentation, VR.US, 0);
        attrs.setInt(Tag.SamplesPerPixel, VR.US, 1);
        attrs.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
        byte[] pixels = new byte[16 * 16 * 2];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) i;
        }
        attrs.setBytes(Tag.PixelData, VR.OW, pixels);
        return attrs;
    }
}
