package com.imagunity.directoryserver;

import com.imagunity.directory.KeyFiles;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Clé de l'organisation, service de publication et accès.
 *
 * <p>Lecture libre : les entrées sont signées et ne contiennent que des clés publiques, des noms et des adresses de
 * transport. Dépôt réservé au compte du responsable (HTTP Basic, sans session ; client en ligne de commande, d'où
 * l'absence de jeton CSRF).
 */
@Configuration(proxyBeanMethods = false)
class DirectoryConfiguration {

    /** Requêtes de l'interface qui modifient ou calculent : en-tête {@link #ADMIN_HEADER} exigé. */
    static final class AdminHeaderFilter extends org.springframework.web.filter.OncePerRequestFilter {
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain chain)
                throws jakarta.servlet.ServletException, IOException {
            if (request.getRequestURI().startsWith("/admin/api/") && !"GET".equals(request.getMethod())
                    && !"1".equals(request.getHeader(ADMIN_HEADER))) {
                response.sendError(403, "En-tête " + ADMIN_HEADER + " manquant");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(DirectoryConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    KeyFiles.OrganizationPublicKey organizationKey(DirectoryServerProperties properties) throws IOException {
        if (properties.organizationKey() == null) {
            throw new IllegalStateException("Clé publique de l'organisation non configurée "
                    + "(IMAGUNITY_DIRECTORY_ORGANIZATION_KEY)");
        }
        KeyFiles.OrganizationPublicKey key = KeyFiles.readOrganizationPublic(properties.organizationKey());
        LOG.info("Annuaire de l'organisation « {} », empreinte du responsable : {}", key.organization(),
                key.key().shortCode());
        return key;
    }

    @Bean
    PublicationService publicationService(EntryStore store, KeyFiles.OrganizationPublicKey key, Clock clock) {
        return new PublicationService(store, key.organization(), key.key(), clock);
    }

    @Bean
    ConfigurationService configurationService(ConfigurationStore store, EntryStore entries,
                                              KeyFiles.OrganizationPublicKey key, Clock clock) {
        return new ConfigurationService(store, entries, key.organization(), key.key(), clock);
    }

    /** En-tête exigé sur les requêtes de l'interface : un autre site ne peut pas le poser (anti-CSRF). */
    static final String ADMIN_HEADER = "X-ImagUnity-Admin";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/directory/v1/entries").hasRole("PUBLISHER")
                        .requestMatchers(HttpMethod.POST, "/directory/v1/configurations").hasRole("PUBLISHER")
                        // Comptes rendus : signés par la box, vérifiés par le service ; sans compte.
                        .requestMatchers(HttpMethod.POST, "/directory/v1/configurations/reports").permitAll()
                        .requestMatchers("/admin", "/admin/", "/admin/**").hasRole("PUBLISHER")
                        .requestMatchers(HttpMethod.GET, "/directory/v1/**", "/actuator/health",
                                "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.realmName("ImagUnity annuaire"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .addFilterAfter(new AdminHeaderFilter(), org.springframework.security.web.authentication.www
                        .BasicAuthenticationFilter.class)
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'; form-action 'self'")))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService publisher(DirectoryServerProperties properties, PasswordEncoder encoder) {
        String password = properties.publisherPassword();
        if (password == null || password.isBlank()) {
            byte[] bytes = new byte[18];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            LOG.warn("Aucun mot de passe de dépôt configuré (IMAGUNITY_DIRECTORY_PUBLISHER_PASSWORD) : mot de passe "
                    + "généré pour ce démarrage, compte « {} » : {}", properties.publisherUser(), password);
        }
        String stored = password.startsWith("{") ? password : encoder.encode(password);
        return new InMemoryUserDetailsManager(User.withUsername(properties.publisherUser()).password(stored)
                .roles("PUBLISHER").build());
    }
}
