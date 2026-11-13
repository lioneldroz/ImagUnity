package com.imagunity.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accès à la console et à l'API : un compte opérateur, en HTTP Basic, sans session.
 *
 * <ul>
 *   <li>{@code /actuator/health} reste libre (supervision, contrôles de santé des conteneurs) ;</li>
 *   <li>{@code /dicomweb/**} (dépôt STOW-RS par le PACS) demande le compte DICOMweb, et lui seul ;</li>
 *   <li>tout le reste demande le compte opérateur ;</li>
 *   <li>les requêtes qui modifient l'état (POST, PUT, PATCH, DELETE) doivent porter l'en-tête
 *       {@value #CONSOLE_HEADER} : un site tiers ne peut pas l'ajouter (CORS fermé), ce qui protège des
 *       requêtes intersites qu'un navigateur authentifié enverrait sinon de lui-même. Le dépôt DICOMweb en est
 *       dispensé : il vient d'un PACS, pas d'un navigateur, avec un compte qui n'ouvre pas la console.</li>
 * </ul>
 * Le compte circule en clair sur HTTP : sur le réseau du site, servir la console derrière un proxy TLS.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String CONSOLE_HEADER = "X-ImagUnity-Console";
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
            + "form-action 'none'; object-src 'none'";
    static final String DICOMWEB = "/dicomweb";
    static final String ROLE_OPERATOR = "OPERATEUR";
    static final String ROLE_DICOMWEB = "DICOMWEB";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(DICOMWEB + "/**").hasRole(ROLE_DICOMWEB)
                        .anyRequest().hasRole(ROLE_OPERATOR))
                .httpBasic(basic -> basic.realmName("ImagUnity"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Pas de jeton CSRF : sans session ni cookie, l'en-tête de la console en tient lieu.
                .csrf(csrf -> csrf.disable())
                .addFilterAfter(new ConsoleHeaderFilter(), BasicAuthenticationFilter.class)
                // La console (servie sur « / ») ne charge rien d'extérieur ni aucun script en ligne.
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService accounts(ConsoleAccessProperties properties, DicomWebProperties dicomweb,
                                PasswordEncoder encoder) {
        InMemoryUserDetailsManager accounts = operator(properties, encoder);
        if (dicomweb.enabled()) {
            if (dicomweb.user().equals(properties.user())) {
                throw new IllegalStateException("Le compte DICOMweb doit être distinct du compte opérateur");
            }
            String stored = dicomweb.password().startsWith("{") ? dicomweb.password()
                    : encoder.encode(dicomweb.password());
            accounts.createUser(User.withUsername(dicomweb.user()).password(stored).roles(ROLE_DICOMWEB).build());
            LOG.info("Réception DICOMweb (STOW-RS) ouverte au compte « {} »", dicomweb.user());
        }
        return accounts;
    }

    InMemoryUserDetailsManager operator(ConsoleAccessProperties properties, PasswordEncoder encoder) {
        String password = properties.password();
        if (password == null || password.isBlank()) {
            password = generatedPassword();
            Path file = properties.generatedPasswordFile();
            writeOwnerOnly(file, password);
            LOG.warn("Aucun mot de passe de console configuré (IMAGUNITY_CONSOLE_PASSWORD) : mot de passe généré "
                    + "pour ce démarrage, compte « {} », écrit dans {} (lisible du seul compte de la box)",
                    properties.user(), file.toAbsolutePath());
        }
        String stored = password.startsWith("{") ? password : encoder.encode(password);
        return new InMemoryUserDetailsManager(User.withUsername(properties.user()).password(stored)
                .roles(ROLE_OPERATOR).build());
    }

    /** Écrit le mot de passe dans un fichier lisible du seul propriétaire (créé ainsi, jamais élargi ensuite). */
    static void writeOwnerOnly(Path file, String password) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".mot-de-passe", ".tmp", ownerOnly(parent));
            Files.writeString(temp, password + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Mot de passe de console généré : écriture de " + file + " impossible",
                    e);
        }
    }

    private static java.nio.file.attribute.FileAttribute<?>[] ownerOnly(Path directory) {
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return new java.nio.file.attribute.FileAttribute<?>[] {java.nio.file.attribute.PosixFilePermissions
                    .asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))};
        }
        return new java.nio.file.attribute.FileAttribute<?>[0];
    }

    static String generatedPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Refuse (403) une requête qui modifie l'état sans l'en-tête de la console. */
    static final class ConsoleHeaderFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (SAFE_METHODS.contains(request.getMethod()) || request.getHeader(CONSOLE_HEADER) != null
                    || request.getRequestURI().startsWith(request.getContextPath() + DICOMWEB + "/")) {
                chain.doFilter(request, response);
                return;
            }
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"En-tête "
                    + CONSOLE_HEADER + " manquant\"}");
        }
    }
}
