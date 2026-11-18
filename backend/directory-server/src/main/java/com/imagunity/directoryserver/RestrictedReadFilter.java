package com.imagunity.directoryserver;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedRead;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Lecture réservée (étape 4 de la fédération). Les listes de l'annuaire (entrées, historique, reconnaissances,
 * configurations, entrées et preuves des partenaires) ne se lisent plus librement : seuls les lisent
 *
 * <ul>
 *   <li>un compte (responsable, ou compte de lecture du relais) ;</li>
 *   <li>une box active de l'organisation, par une lecture signée avec la clé de son entrée ;</li>
 *   <li>le service d'annuaire d'un partenaire reconnu, par une lecture signée avec sa clé de fraîcheur déléguée
 *       (d'après sa dernière preuve relayée) ; il ne lit que la liste des entrées.</li>
 * </ul>
 *
 * <p>Restent publiques : l'organisation, la preuve de fraîcheur et l'entrée d'une box donnée (une box qui s'enrôle
 * lit la sienne). Les entrées sont signées et ne contiennent pas de secret : la réserve évite qu'un inconnu dresse
 * la carte des établissements et des adresses.
 */
final class RestrictedReadFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RestrictedReadFilter.class);
    private static final String API = "/directory/v1";
    private static final Pattern HISTORY = Pattern.compile(API + "/entries/[^/]+/history");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    /** Qui peut lire ce chemin. */
    enum Access {
        PUBLIC, BOXES, ACCOUNTS_AND_BOXES_AND_PARTNERS, ACCOUNTS
    }

    private final boolean enabled;
    private final EntryStore entries;
    private final FederationService federation;
    private final String organization;
    private final HybridPublicKey organizationKey;
    private final Clock clock;

    RestrictedReadFilter(boolean enabled, EntryStore entries, FederationService federation, String organization,
                         HybridPublicKey organizationKey, Clock clock) {
        this.enabled = enabled;
        this.entries = entries;
        this.federation = federation;
        this.organization = organization;
        this.organizationKey = organizationKey;
        this.clock = clock;
    }

    static Access access(String path) {
        if (path.equals(API + "/entries")) {
            return Access.ACCOUNTS_AND_BOXES_AND_PARTNERS;
        }
        if (HISTORY.matcher(path).matches()) {
            return Access.ACCOUNTS;
        }
        if (path.equals(API + "/recognitions") || path.equals(API + "/configurations")
                || path.startsWith(API + "/partners/") || path.equals(API + "/events")
                || path.startsWith(API + "/test-requests/")) {
            return Access.BOXES;
        }
        return Access.PUBLIC;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || !"GET".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        // Le chemin classé est le chemin décodé, tel que le routage de Spring le voit : classer l'URI brute laisserait
        // passer « /directory/v1/%65ntries » pour public alors qu'il mène à la liste des entrées. L'URI brute ne sert
        // qu'à vérifier la signature (le lecteur signe le chemin qu'il envoie).
        Access access = access(PATHS.getPathWithinApplication(request));
        if (access == Access.PUBLIC || account()) {
            chain.doFilter(request, response);
            return;
        }
        String reader = request.getHeader(SignedRead.READER);
        if (reader == null) {
            refuse(response, "Lecture réservée : compte ou lecture signée exigé");
            return;
        }
        String pathAndQuery = request.getRequestURI() + (request.getQueryString() == null ? ""
                : "?" + request.getQueryString());
        Optional<HybridPublicKey> key;
        if (reader.startsWith(SignedRead.ORGANIZATION)) {
            String partner = reader.substring(SignedRead.ORGANIZATION.length());
            key = access == Access.ACCOUNTS_AND_BOXES_AND_PARTNERS && FINGERPRINT.matcher(partner).matches()
                    ? federation.partnerReadKey(partner) : Optional.empty();
        } else {
            key = access == Access.ACCOUNTS ? Optional.empty() : activeBoxKey(reader);
        }
        if (key.isEmpty() || !SignedRead.verify(reader, pathAndQuery, request.getHeader(SignedRead.TIMESTAMP),
                request.getHeader(SignedRead.SIGNATURE), key.get(), clock.instant())) {
            LOG.debug("Lecture réservée refusée : {} sur {}", reader, request.getRequestURI());
            refuse(response, "Lecture réservée : lecteur inconnu, inactif ou signature invalide");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean account() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_PUBLISHER") || a.getAuthority().equals("ROLE_READER"));
    }

    /** Clé de signature d'une box active de l'organisation (entrée courante revérifiée). */
    private Optional<HybridPublicKey> activeBoxKey(String reader) {
        UUID boxId;
        try {
            boxId = UUID.fromString(reader);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return entries.current(boxId).flatMap(document -> {
            try {
                EntryVerifier.Verified verified = new EntryVerifier(organization, organizationKey,
                        SerialMemory.inMemory(), clock).verify(document);
                return verified.status() == BoxEntry.Status.ACTIVE ? Optional.of(verified.entry().signingKey())
                        : Optional.empty();
            } catch (DirectoryException e) {
                return Optional.empty();
            }
        });
    }

    private static void refuse(HttpServletResponse response, String message) throws IOException {
        response.setHeader("WWW-Authenticate", "Basic realm=\"ImagUnity annuaire\"");
        response.sendError(401, message);
    }
}
