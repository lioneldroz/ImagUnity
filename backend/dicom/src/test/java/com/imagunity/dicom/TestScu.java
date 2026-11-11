package com.imagunity.dicom;

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

/** Client DICOM de test (joue le rôle du PACS local), construit avec dcm4che. */
final class TestScu implements AutoCloseable {

    private final Device device = new Device("test-scu");
    private final ApplicationEntity ae;
    private final Connection local = new Connection();
    private final Connection remote = new Connection();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    TestScu(String callingAet, int port) {
        ae = new ApplicationEntity(callingAet);
        device.addConnection(local);
        device.addApplicationEntity(ae);
        ae.addConnection(local);
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);
        remote.setHostname("127.0.0.1");
        remote.setPort(port);
    }

    /** Ouvre une association vers la destination donnée (lève une exception si elle est refusée). */
    Association associate(String calledAet) throws Exception {
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(calledAet);
        rq.addPresentationContext(new PresentationContext(1, UID.Verification, UID.ImplicitVRLittleEndian));
        rq.addPresentationContext(new PresentationContext(3, UID.CTImageStorage, UID.ExplicitVRLittleEndian));
        return ae.connect(remote, rq);
    }

    /** Envoie les instances dans une association ; renvoie le statut DIMSE de chacune. */
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

    /** Envoie une instance dont le SOP Instance UID de la commande diffère de celui du jeu de données. */
    int storeWithCommandUid(String calledAet, Attributes attrs, String commandSopInstanceUid) throws Exception {
        Association as = associate(calledAet);
        try {
            DimseRSP rsp = as.cstore(attrs.getString(Tag.SOPClassUID), commandSopInstanceUid, Priority.NORMAL,
                    new DataWriterAdapter(attrs), UID.ExplicitVRLittleEndian);
            rsp.next();
            return rsp.getCommand().getInt(Tag.Status, -1);
        } finally {
            as.release();
            as.waitForSocketClose();
        }
    }

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
