package com.imagunity.dicom;

import java.io.IOException;

/** Démarrage d'un SCP de test sur un port qui vient peut-être d'être libéré. */
final class TestPorts {

    private TestPorts() {
    }

    /**
     * Démarre le SCP en retentant pendant au plus 5 s : après l'arrêt d'un SCP, le système peut mettre
     * un court instant à libérer son port (observé en CI sous Linux).
     */
    static void start(DicomStoreScp scp) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                scp.start();
                return;
            } catch (IOException e) {
                last = e;
                scp.stop();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }
}
