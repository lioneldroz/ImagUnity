package com.imagunity.config;

import com.imagunity.packaging.PackagingConfig;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Orchestrateur et empaquetage ({@code imagunity.orchestration.*}).
 *
 * @param outboxDir        dossier d'envoi : paquets en attente ou en cours de transfert
 * @param pairingTimeout   durée de validité d'un appairage en cours
 * @param maxPartSize      taille maximale du contenu d'un paquet (900 Mo par défaut, sous la limite XFTP)
 * @param compressionLevel niveau de compression zstd
 */
@ConfigurationProperties("imagunity.orchestration")
public record OrchestrationProperties(
        @DefaultValue("./data/outbox") Path outboxDir,
        @DefaultValue("1h") Duration pairingTimeout,
        @DefaultValue("900MB") DataSize maxPartSize,
        @DefaultValue("3") int compressionLevel) {

    PackagingConfig packagingConfig() {
        return new PackagingConfig(maxPartSize.toBytes(), compressionLevel);
    }
}
