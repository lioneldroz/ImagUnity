package com.imagunity.config;

import com.imagunity.dicom.RestoreConfig;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Réception et restitution au PACS local ({@code imagunity.restore.*}).
 *
 * @param pacsHost           adresse du PACS local
 * @param pacsPort           port DICOM du PACS local
 * @param pacsAet            AET du PACS local
 * @param boxAet             AET sous lequel la box se présente au PACS (à y autoriser)
 * @param inboxDir           dossier de réception des paquets
 * @param maxInboundFileSize taille maximale d'un paquet accepté d'un partenaire
 * @param maxUnpackedSize    volume décompressé maximal d'un paquet (protection contre les bombes de décompression)
 */
@ConfigurationProperties("imagunity.restore")
public record RestoreProperties(
        @DefaultValue("localhost") String pacsHost,
        @DefaultValue("4242") int pacsPort,
        @DefaultValue("ORTHANC") String pacsAet,
        @DefaultValue("IMAGUNITY") String boxAet,
        @DefaultValue("./data/inbox") Path inboxDir,
        @DefaultValue("2GB") DataSize maxInboundFileSize,
        @DefaultValue("4GB") DataSize maxUnpackedSize) {

    RestoreConfig restoreConfig() {
        return new RestoreConfig(pacsHost, pacsPort, pacsAet, boxAet);
    }
}
