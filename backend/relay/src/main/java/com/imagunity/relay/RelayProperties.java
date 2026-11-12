package com.imagunity.relay;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configuration du relais ({@code imagunity.relay.*}).
 *
 * @param storageDir    dossier du contenu des morceaux
 * @param defaultTtl    durée de séjour d'un morceau si la box n'en demande pas
 * @param maxTtl        durée de séjour maximale
 * @param incompleteTtl durée de séjour d'un morceau créé mais jamais téléversé
 * @param maxWait       attente longue maximale d'un morceau pas encore téléversé
 * @param quotaPerBox   volume maximal déposé par une box
 * @param minFreeSpace  espace disque à garder libre
 * @param directory     annuaire de l'organisation
 */
@ConfigurationProperties("imagunity.relay")
public record RelayProperties(
        @DefaultValue("./data/morceaux") Path storageDir,
        @DefaultValue("72h") Duration defaultTtl,
        @DefaultValue("7d") Duration maxTtl,
        @DefaultValue("24h") Duration incompleteTtl,
        @DefaultValue("30s") Duration maxWait,
        @DefaultValue("100GB") DataSize quotaPerBox,
        @DefaultValue("1GB") DataSize minFreeSpace,
        @DefaultValue Directory directory) {

    /**
     * @param url              service d'annuaire
     * @param organizationKey  clé publique du responsable ({@code .pub.json})
     * @param organizationCode son empreinte courte, vérifiée hors bande
     * @param syncInterval     intervalle entre deux lectures de l'annuaire
     */
    public record Directory(URI url, Path organizationKey, String organizationCode,
                            @DefaultValue("5m") Duration syncInterval) {
    }
}
