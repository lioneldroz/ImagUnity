package com.imagunity.scenarios;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DataWriterAdapter;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;

/** PACS émetteur simulé : envoie des instances en C-STORE à l'entrée DICOM d'une box. */
final class PacsClient implements AutoCloseable {

    private final Device device = new Device("pacs-emetteur-simule");
    private final ApplicationEntity ae;
    private final Connection local = new Connection();
    private final Connection remote = new Connection();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * @param callingAet AET de ce PACS (la box n'accepte que ses émetteurs déclarés)
     * @param port       port DICOM de la box
     */
    PacsClient(String callingAet, int port) {
        ae = new ApplicationEntity(callingAet);
        device.addConnection(local);
        device.addApplicationEntity(ae);
        ae.addConnection(local);
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);
        remote.setHostname("127.0.0.1");
        remote.setPort(port);
    }

    /** Ouvre une association vers la destination {@code calledAet} ; lève une exception si elle est refusée. */
    Association associate(String calledAet) throws Exception {
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(calledAet);
        rq.addPresentationContext(new PresentationContext(1, UID.Verification, UID.ImplicitVRLittleEndian));
        rq.addPresentationContext(new PresentationContext(3, UID.CTImageStorage, UID.ExplicitVRLittleEndian));
        rq.addPresentationContext(new PresentationContext(5, UID.MRImageStorage, UID.ExplicitVRLittleEndian));
        return ae.connect(remote, rq);
    }

    /** Envoie les instances dans une même association ; rend le statut DIMSE de chacune (0 : acceptée). */
    List<Integer> store(String calledAet, List<Attributes> instances) throws Exception {
        Association as = associate(calledAet);
        try {
            return instances.stream().map(attrs -> {
                try {
                    DimseRSP rsp = as.cstore(attrs.getString(Tag.SOPClassUID), attrs.getString(Tag.SOPInstanceUID),
                            Priority.NORMAL, new DataWriterAdapter(attrs), UID.ExplicitVRLittleEndian);
                    rsp.next();
                    return rsp.getCommand().getInt(Tag.Status, -1);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        } finally {
            as.release();
            as.waitForSocketClose();
        }
    }

    /** C-ECHO : rend le statut DIMSE (0 : la box répond). */
    int echo(String calledAet) throws Exception {
        Association as = associate(calledAet);
        try {
            DimseRSP rsp = as.cecho();
            rsp.next();
            return rsp.getCommand().getInt(Tag.Status, -1);
        } finally {
            as.release();
            as.waitForSocketClose();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        scheduler.shutdownNow();
    }
}
