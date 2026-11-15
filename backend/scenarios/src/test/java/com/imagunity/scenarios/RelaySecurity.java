package com.imagunity.scenarios;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Le relais ne dépend pas de Spring Security : il authentifie lui-même chaque requête signée par une box. Sur le
 * classpath des scénarios, Spring Security est présent (box, service d'annuaire) et son autoconfiguration fermerait
 * toutes ses routes ; cette chaîne laisse passer, comme en production, toutes les requêtes jusqu'au relais.
 */
@Configuration(proxyBeanMethods = false)
class RelaySecurity {

    @Bean
    SecurityFilterChain relayChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                .build();
    }
}
