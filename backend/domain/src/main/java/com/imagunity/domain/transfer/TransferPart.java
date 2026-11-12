package com.imagunity.domain.transfer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Un paquet d'un transfert, et son avancement dans le transport.
 *
 * @param transferId      transfert auquel le paquet appartient
 * @param index           rang du paquet, à partir de 1
 * @param count           nombre de paquets du transfert
 * @param file            fichier du paquet (dossier d'envoi de la box)
 * @param size            taille du fichier, en octets
 * @param sha256          empreinte SHA-256 du fichier
 * @param transportFileId identifiant du fichier dans le transport, une fois confié ({@code null} avant)
 * @param uploaded        vrai quand le transport a fini de le déposer sur les relais
 */
public record TransferPart(
        UUID transferId,
        int index,
        int count,
        Path file,
        long size,
        String sha256,
        Long transportFileId,
        boolean uploaded) {

    public TransferPart {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(sha256, "sha256");
    }

    /** Paquet remplacé par sa version chiffrée, pas encore confiée au transport. */
    public TransferPart sealedAs(Path sealed, long sealedSize, String sealedSha256) {
        return new TransferPart(transferId, index, count, sealed, sealedSize, sealedSha256, null, false);
    }

    public TransferPart handedOver(long fileId) {
        return new TransferPart(transferId, index, count, file, size, sha256, fileId, false);
    }

    /** Paquet à confier de nouveau au transport (relance d'un transfert). */
    public TransferPart reset() {
        return new TransferPart(transferId, index, count, file, size, sha256, null, false);
    }

    public TransferPart markUploaded() {
        return new TransferPart(transferId, index, count, file, size, sha256, transportFileId, true);
    }
}
