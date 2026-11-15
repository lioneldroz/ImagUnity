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
 * <p>Lecture libre par défaut : les entrées sont signées et ne contiennent que des clés publiques, des noms et des
 * adresses de transport. Lecture réservée sur option ({@link RestrictedReadFilter}). Dépôt réservé au compte du responsable (HTTP Basic, sans session ; client en ligne de commande, d'où
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
            // Chemin décodé, comme pour le routage : « /admin/%61pi/… » ne doit pas échapper à la vérification.
            String path = new org.springframework.web.util.UrlPathHelper().getPathWithinApplication(request);
            if (path.startsWith("/admin/api/") && !"GET".equals(request.getMethod())
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
        // Clé épinglée, comme au relais et dans les box : un fichier remplacé en chemin, ou celui d'une autre
        // organisation, se voit dès le démarrage au lieu de faire refuser chaque dépôt.
        String actual = key.key().shortCode();
        if (!normalize(actual).equals(normalize(properties.organizationCode()))) {
            throw new IllegalStateException("Empreinte du responsable non confirmée : la clé installée ("
                    + properties.organizationKey() + ", organisation « " + key.organization() + " ») a l'empreinte "
                    + actual + " ; après l'avoir vérifiée hors bande avec le responsable, la recopier dans "
                    + "IMAGUNITY_DIRECTORY_ORGANIZATION_CODE (site central : EMPREINTE_RESPONSABLE)");
        }
        LOG.info("Annuaire de l'organisation « {} » : clé du responsable épinglée ({})", key.organization(), actual);
        return key;
    }

    /** Empreinte sans tirets ni espaces, en majuscules : la casse et la mise en forme ne comptent pas. */
    static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
    }

    @Bean
    PublicationService publicationService(EntryStore store, EnrollmentStore enrollments,
                                          KeyFiles.OrganizationPublicKey key, Clock clock) {
        return new PublicationService(store, key.organization(), key.key(), clock, enrollments::delete);
    }

    @Bean
    EnrollmentService enrollmentService(EnrollmentStore store, EntryStore entries, KeyFiles.OrganizationPublicKey key,
                                        Clock clock) {
        return new EnrollmentService(store, entries, key.organization(), key.key(), clock);
    }

    @Bean
    ConfigurationService configurationService(ConfigurationStore store, EntryStore entries,
                                              KeyFiles.OrganizationPublicKey key, Clock clock) {
        return new ConfigurationService(store, entries, key.organization(), key.key(), clock);
    }

    @Bean
    FederationService federationService(RecognitionStore store, KeyFiles.OrganizationPublicKey key, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${imagunity.directory.freshness-key:}") String keyFile)
            throws IOException {
        // Lectures des annuaires partenaires signées par la clé de fraîcheur : elles y passent la lecture réservée.
        com.imagunity.directory.SignedRead reader = keyFile.isBlank() ? null : new com.imagunity.directory.SignedRead(
                com.imagunity.directory.SignedRead.ORGANIZATION + key.key().fingerprint(),
                KeyFiles.readPrivate(java.nio.file.Path.of(keyFile)));
        return new FederationService(store, key.organization(), key.key(), clock, reader);
    }

    /**
     * Preuve de fraîcheur : clé déléguée par le responsable ({@code imagunity.directory.freshness-key}, fichier de clé
     * privée produit par {@code fraicheur-deleguer}) et sa délégation ({@code freshness-delegation}). Sans elles,
     * l'annuaire ne publie pas de preuve.
     */
    @Bean
    FreshnessService freshnessService(EntryStore entries, RecognitionStore recognitions,
            KeyFiles.OrganizationPublicKey key, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${imagunity.directory.freshness-key:}") String keyFile,
            @org.springframework.beans.factory.annotation.Value("${imagunity.directory.freshness-delegation:}")
            String delegationFile,
            @org.springframework.beans.factory.annotation.Value("${imagunity.directory.freshness-interval:10m}")
            java.time.Duration interval) throws IOException {
        if (keyFile.isBlank()) {
            return new FreshnessService(entries, recognitions, key.organization(), key.key(), null, null, interval,
                    clock);
        }
        if (delegationFile.isBlank()) {
            throw new IllegalStateException("Clé de fraîcheur sans sa délégation (IMAGUNITY_DIRECTORY_FRESHNESS_DELEGATION)");
        }
        return new FreshnessService(entries, recognitions, key.organization(), key.key(),
                KeyFiles.readPrivate(java.nio.file.Path.of(keyFile)),
                com.imagunity.directory.SignedDocument.fromJson(java.nio.file.Files.readString(
                        java.nio.file.Path.of(delegationFile))), interval, clock);
    }

    /**
     * Relais des entrées des partenaires : lecture de leur annuaire au démarrage puis à intervalle régulier
     * ({@code imagunity.directory.partner-sync-interval}, 5 minutes par défaut).
     */
    @Bean(destroyMethod = "shutdownNow")
    java.util.concurrent.ScheduledExecutorService partnerSync(FederationService federation,
            @org.springframework.beans.factory.annotation.Value("${imagunity.directory.partner-sync-interval:5m}")
            java.time.Duration interval) {
        java.util.concurrent.ScheduledExecutorService executor = java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor(Thread.ofPlatform().name("partenaires").daemon().factory());
        executor.scheduleWithFixedDelay(() -> {
            try {
                federation.refreshPartners();
            } catch (RuntimeException e) {
                LOG.warn("Relais des entrées des partenaires : {}", e.getMessage());
            }
        }, 10, interval.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        return executor;
    }

    /** En-tête exigé sur les requêtes de l'interface : un autre site ne peut pas le poser (anti-CSRF). */
    static final String ADMIN_HEADER = "X-ImagUnity-Admin";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DirectoryServerProperties properties,
            EntryStore entries, FederationService federation, KeyFiles.OrganizationPublicKey key, Clock clock)
            throws Exception {
        if (properties.restrictedRead()) {
            LOG.info("Lecture réservée : listes de l'annuaire lisibles des seuls box actives, partenaires et comptes");
        }
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/directory/v1/entries").hasRole("PUBLISHER")
                        .requestMatchers(HttpMethod.POST, "/directory/v1/configurations").hasRole("PUBLISHER")
                        .requestMatchers(HttpMethod.POST, "/directory/v1/recognitions").hasRole("PUBLISHER")
                        // Comptes rendus : signés par la box, vérifiés par le service ; sans compte.
                        .requestMatchers(HttpMethod.POST, "/directory/v1/configurations/reports").permitAll()
                        // Demandes d'enrôlement : déposées par les box (sans compte), lues par le responsable.
                        .requestMatchers(HttpMethod.POST, "/directory/v1/enrollment-requests").permitAll()
                        .requestMatchers(HttpMethod.GET, "/directory/v1/enrollment-requests").hasRole("PUBLISHER")
                        .requestMatchers(HttpMethod.GET, "/directory/v1/configurations/reports/*").hasRole("PUBLISHER")
                        .requestMatchers(HttpMethod.DELETE, "/directory/v1/enrollment-requests/*").hasRole("PUBLISHER")
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
                .addFilterAfter(new RestrictedReadFilter(properties.restrictedRead(), entries, federation,
                        key.organization(), key.key(), clock), AdminHeaderFilter.class)
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'; form-action 'self'")))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Écrit le mot de passe dans un fichier lisible du seul propriétaire. */
    static void writeOwnerOnly(java.nio.file.Path file, String password) {
        try {
            java.nio.file.Path parent = file.toAbsolutePath().getParent();
            java.nio.file.Files.createDirectories(parent);
            java.nio.file.attribute.FileAttribute<?>[] attributes = parent.getFileSystem().supportedFileAttributeViews()
                    .contains("posix") ? new java.nio.file.attribute.FileAttribute<?>[] {
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}
                    : new java.nio.file.attribute.FileAttribute<?>[0];
            java.nio.file.Path temp = java.nio.file.Files.createTempFile(parent, ".mot-de-passe", ".tmp", attributes);
            java.nio.file.Files.writeString(temp, password + System.lineSeparator());
            java.nio.file.Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Mot de passe de dépôt généré : écriture de " + file
                    + " impossible", e);
        }
    }

    @Bean
    UserDetailsService publisher(DirectoryServerProperties properties, PasswordEncoder encoder) {
        String password = properties.publisherPassword();
        if (password == null || password.isBlank()) {
            byte[] bytes = new byte[18];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            java.nio.file.Path file = properties.generatedPasswordFile();
            writeOwnerOnly(file, password);
            LOG.warn("Aucun mot de passe de dépôt configuré (IMAGUNITY_DIRECTORY_PUBLISHER_PASSWORD) : mot de passe "
                    + "généré pour ce démarrage, compte « {} », écrit dans {} (lisible du seul compte du service)",
                    properties.publisherUser(), file.toAbsolutePath());
        }
        String stored = password.startsWith("{") ? password : encoder.encode(password);
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager(User.withUsername(properties.publisherUser())
                .password(stored).roles("PUBLISHER").build());
        String reader = properties.readerPassword();
        if (reader != null && !reader.isBlank()) {
            users.createUser(User.withUsername(properties.readerUser()).password(reader.startsWith("{") ? reader
                    : encoder.encode(reader)).roles("READER").build());
        }
        return users;
    }
}
