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
 * @param maxQueuesPerBox nombre maximal de files de messages d'une box
 * @param directory     annuaire de l'organisation
 * @param storage       stockage du contenu des morceaux : disque ({@code storageDir}) ou service compatible S3
 * @param instanceId    nom de cette instance dans les journaux et les baux (plusieurs instances derrière un
 *                      répartiteur) ; par défaut, le nom de la machine
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
        @DefaultValue("4") int maxQueuesPerBox,
        @DefaultValue Directory directory,
        @DefaultValue Storage storage,
        String instanceId) {

    /** Nom de cette instance : configuré, sinon le nom de la machine. */
    public String instance() {
        if (instanceId != null && !instanceId.isBlank()) {
            return instanceId.strip();
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "relais-" + ProcessHandle.current().pid();
        }
    }

    /**
     * Stockage du contenu des morceaux. Les fichiers temporaires de téléversement restent dans {@code storageDir}.
     *
     * @param type      {@code disk} (une instance, ou un volume partagé) ou {@code s3} (plusieurs instances)
     * @param endpoint  adresse du service S3 ; vide pour AWS
     * @param region    région (souvent indifférente hors AWS)
     * @param bucket    compartiment, créé au besoin
     * @param prefix    préfixe des objets
     * @param accessKey identifiant du compte S3
     * @param secretKey secret du compte S3 (à fournir par l'environnement, jamais dans un fichier versionné)
     * @param pathStyle adressage par chemin ({@code http://service/compartiment/objet}), requis par la plupart des
     *                  services autres qu'AWS
     * @param startupPatience attente du service S3 au démarrage du relais
     */
    public record Storage(@DefaultValue("disk") String type, URI endpoint, @DefaultValue("us-east-1") String region,
                          @DefaultValue("imagunity-relais") String bucket, @DefaultValue("morceaux/") String prefix,
                          String accessKey, String secretKey, @DefaultValue("true") boolean pathStyle,
                          @DefaultValue("2m") java.time.Duration startupPatience) {

        public boolean s3() {
            return "s3".equalsIgnoreCase(type);
        }

        @Override
        public String toString() {
            return "Storage[type=" + type + ", endpoint=" + endpoint + ", bucket=" + bucket + "]"; // sans le secret
        }
    }

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
