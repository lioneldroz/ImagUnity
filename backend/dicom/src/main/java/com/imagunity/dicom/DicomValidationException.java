package com.imagunity.dicom;

import java.io.IOException;

/** Instance reçue d'un partenaire refusée avant tout envoi au PACS local. */
public class DicomValidationException extends IOException {

    public DicomValidationException(String message) {
        super(message);
    }

    public DicomValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
