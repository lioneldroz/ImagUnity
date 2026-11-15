package com.imagunity.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limites de débit du relais ({@code imagunity.relay.limits.*}), en requêtes par minute ; 0 désactive une limite.
 *
 * @param depositsPerMinute        requêtes de dépôt d'une box de l'organisation (files, morceaux, messages)
 * @param partnerDepositsPerMinute dépôts de messages d'une box d'une organisation partenaire (fédération)
 * @param failedPerMinute          requêtes mal signées venant d'une même adresse IP : au-delà, l'adresse est
 *                                 refusée (429) avant toute vérification de signature
 */
@ConfigurationProperties("imagunity.relay.limits")
public record RateLimitProperties(@DefaultValue("600") int depositsPerMinute,
                                  @DefaultValue("120") int partnerDepositsPerMinute,
                                  @DefaultValue("30") int failedPerMinute) {

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(600, 120, 30);
    }
}
