package com.imagunity.domain.transfer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistance des transferts et de leurs paquets (port ; implémenté par le module persistence). */
public interface TransferRepository {

    void create(Transfer transfer);

    /** Enregistre l'état courant d'un transfert existant. */
    void update(Transfer transfer);

    Optional<Transfer> find(UUID id);

    /**
     * Transferts non terminés de cette étude, reçue sur cette destination DICOM, vers ce partenaire, du plus récent
     * au plus ancien. Il peut y en avoir plusieurs : des instances arrivées après l'empaquetage d'une étude partent
     * dans un transfert à elles.
     */
    List<Transfer> findActive(String studyInstanceUid, String calledAet, String destination);

    List<Transfer> findByStates(Set<TransferState> states);

    /** Derniers transferts, du plus récemment modifié au plus ancien. */
    List<Transfer> findRecent(int limit);

    /** Nombre de transferts par état (états sans transfert absents). */
    Map<TransferState, Long> countByState();

    /** Remplace les paquets d'un transfert. */
    void saveParts(UUID transferId, List<TransferPart> parts);

    /** Met à jour un paquet : fichier, taille et empreinte (après chiffrement), avancement dans le transport. */
    void updatePart(TransferPart part);

    /** Paquets d'un transfert, par rang croissant. */
    List<TransferPart> parts(UUID transferId);

    Optional<TransferPart> findPartByTransportFileId(long transportFileId);

    /** Enregistre les instances (SOP Instance UID) empaquetées dans un transfert. */
    void saveInstances(UUID transferId, Set<String> sopInstanceUids);

    /** Instances empaquetées dans un transfert (vide pour un transfert d'une version antérieure). */
    Set<String> instances(UUID transferId);
}
