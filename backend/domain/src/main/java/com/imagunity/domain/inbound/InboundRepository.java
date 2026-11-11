package com.imagunity.domain.inbound;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistance de la réception : fichiers proposés et transferts annoncés (port). */
public interface InboundRepository {

    void saveFile(InboundFile file);

    Optional<InboundFile> findFile(long transportFileId);

    /** Fichiers d'un partenaire portant ce nom, du plus récent au plus ancien. */
    List<InboundFile> findFiles(String peerName, String fileName);

    void saveTransfer(InboundTransfer transfer);

    Optional<InboundTransfer> findTransfer(String peerName, UUID transferId);

    Optional<InboundTransfer> findTransferByGroup(String peerName, String packageGroupId);

    List<InboundTransfer> findTransfers(InboundTransfer.State state);

    /** Transferts terminés dont l'accusé n'est pas encore parti (cf. {@link InboundTransfer#awaitsAck()}). */
    List<InboundTransfer> findAwaitingAck();
}
