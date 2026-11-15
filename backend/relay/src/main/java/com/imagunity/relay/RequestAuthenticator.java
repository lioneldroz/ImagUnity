package com.imagunity.relay;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.ProtocolException;
import com.imagunity.protocol.SignedRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Vérifie les requêtes signées (spécification § 6.3, 6.4) : horodatage et anti-rejeu, puis
 * <ul>
 *   <li>dépôt : box enrôlée et <b>active</b> dans l'annuaire, double signature de son identité. Une box d'une
 *       organisation partenaire (fédération) ne peut que déposer des messages, dans les files des box locales que
 *       couvre l'accord ;</li>
 *   <li>capacité : signature Ed25519 de la clé du morceau ou de la file, sans identité.</li>
 * </ul>
 * Limites de débit ({@link RateLimitProperties}) : requêtes de dépôt par box, plus basses pour les box des partenaires ;
 * une adresse qui envoie trop de requêtes mal signées est refusée avant toute vérification de signature.
 */
public final class RequestAuthenticator {

    private final LocalDirectory directory;
    private final ReplayStore replay;
    private final Clock clock;
    private final RateLimiter limiter;
    private final RateLimitProperties limits;

    public RequestAuthenticator(LocalDirectory directory, ReplayStore replay, Clock clock) {
        this(directory, replay, clock, new RateLimiter(clock), RateLimitProperties.defaults());
    }

    public RequestAuthenticator(LocalDirectory directory, ReplayStore replay, Clock clock, RateLimiter limiter,
                                RateLimitProperties limits) {
        this.directory = directory;
        this.replay = replay;
        this.clock = clock;
        this.limiter = limiter;
        this.limits = limits;
    }

    /** Adresse ayant envoyé trop de requêtes mal signées : refusée avant toute vérification (coûteuse). */
    private void checkFailures(HttpServletRequest request) {
        long wait = limiter.peek("echecs:" + request.getRemoteAddr(), limits.failedPerMinute());
        if (wait > 0) {
            throw new RelayException(HttpStatus.TOO_MANY_REQUESTS, "Trop de requêtes mal signées depuis cette adresse",
                    wait);
        }
    }

    private RelayException failed(HttpServletRequest request, RelayException e) {
        limiter.acquire("echecs:" + request.getRemoteAddr(), limits.failedPerMinute());
        return e;
    }

    /**
     * Requête de dépôt.
     *
     * @return la box déposante
     * @throws RelayException 401 si la requête est mal signée ou rejouée, 403 si la box n'est pas active
     */
    public UUID depositor(HttpServletRequest request, String bodySha256) {
        checkFailures(request);
        String keyId = request.getHeader(SignedRequest.KEY_ID);
        UUID boxId;
        try {
            boxId = UUID.fromString(keyId == null ? "" : keyId);
        } catch (IllegalArgumentException e) {
            throw failed(request, new RelayException(HttpStatus.UNAUTHORIZED, "Identifiant de box absent ou invalide"));
        }
        SignedRequest signed = parsed(request, bodySha256);
        // Box inconnue ou signature invalide : même réponse, sans détail (pas d'indication sur l'annuaire à un tiers).
        Optional<BoxEntry> entry = directory.entry(boxId);
        if (entry.isEmpty() || !signed.verifyDeposit(entry.get().signingKey(),
                request.getHeader(SignedRequest.SIGNATURE))) {
            throw failed(request, new RelayException(HttpStatus.UNAUTHORIZED, "Requête de dépôt refusée"));
        }
        boolean local = directory.isLocal(boxId);
        long wait = limiter.acquire("box:" + boxId, local ? limits.depositsPerMinute()
                : limits.partnerDepositsPerMinute());
        if (wait > 0) {
            throw new RelayException(HttpStatus.TOO_MANY_REQUESTS, "Trop de requêtes de cette box : nouvel essai dans "
                    + wait + " s", wait);
        }
        BoxEntry.Status status = entry.get().statusAt(clock.instant());
        if (status != BoxEntry.Status.ACTIVE) {
            throw new RelayException(HttpStatus.FORBIDDEN, "Box non active dans l'annuaire (" + status + ")");
        }
        admit(signed);
        return boxId;
    }

    /**
     * Requête de dépôt d'une box de l'organisation : création de file ou de morceau.
     *
     * @throws RelayException 401 ou 403 comme {@link #depositor}, et 403 pour une box d'une organisation partenaire
     */
    public UUID localDepositor(HttpServletRequest request, String bodySha256) {
        UUID boxId = depositor(request, bodySha256);
        if (!directory.isLocal(boxId)) {
            throw new RelayException(HttpStatus.FORBIDDEN, "Box d'une organisation partenaire : seul le dépôt de "
                    + "messages lui est ouvert sur ce relais");
        }
        return boxId;
    }

    /**
     * Dépôt d'un message dans la file d'une box locale : toujours permis entre box de l'organisation ; pour une box
     * d'un partenaire, il faut un accord actif qui couvre les deux box.
     *
     * @throws RelayException 403 sinon
     */
    public void requireExchange(UUID owner, UUID depositor) {
        if (directory.isLocal(depositor)) {
            return;
        }
        if (!directory.isLocal(owner) || !directory.exchangeAllowed(owner, depositor)) {
            throw new RelayException(HttpStatus.FORBIDDEN, "Dépôt hors de l'accord avec l'organisation de cette box");
        }
    }

    /**
     * Requête de capacité sur le morceau ou la file {@code id}, signée par la clé {@code publicKey}.
     *
     * @throws RelayException 401 si la requête est mal signée ou rejouée
     */
    public void capability(HttpServletRequest request, String bodySha256, String id, String publicKey) {
        checkFailures(request);
        if (!id.equals(request.getHeader(SignedRequest.KEY_ID))) {
            throw failed(request, new RelayException(HttpStatus.UNAUTHORIZED,
                    "Identifiant de clé absent ou différent du chemin"));
        }
        SignedRequest signed = parsed(request, bodySha256);
        if (!signed.verifyCapability(publicKey(publicKey), request.getHeader(SignedRequest.SIGNATURE))) {
            throw failed(request, new RelayException(HttpStatus.UNAUTHORIZED, "Signature invalide"));
        }
        admit(signed);
    }

    /**
     * Requête signée lue et dans la fenêtre d'horodatage ; son identifiant n'est enregistré ({@link #admit}) qu'une
     * fois la signature vérifiée : une requête non signée n'écrit rien en base.
     */
    private SignedRequest parsed(HttpServletRequest request, String bodySha256) {
        SignedRequest signed;
        try {
            String timestamp = request.getHeader(SignedRequest.TIMESTAMP);
            if (timestamp == null) {
                throw new ProtocolException("Horodatage absent");
            }
            signed = new SignedRequest(request.getMethod(), request.getRequestURI(), Long.parseLong(timestamp),
                    request.getHeader(SignedRequest.REQUEST_ID), bodySha256);
        } catch (ProtocolException | NumberFormatException e) {
            throw failed(request, new RelayException(HttpStatus.UNAUTHORIZED, "Requête signée mal formée : "
                    + e.getMessage()));
        }
        Optional<String> outside = replay.outsideWindow(signed);
        if (outside.isPresent()) {
            throw new RelayException(HttpStatus.UNAUTHORIZED, "Requête refusée : " + outside.get());
        }
        return signed;
    }

    private void admit(SignedRequest signed) {
        Optional<String> refusal = replay.admit(signed);
        if (refusal.isPresent()) {
            throw new RelayException(HttpStatus.UNAUTHORIZED, "Requête refusée : " + refusal.get());
        }
    }

    /** Clé publique Ed25519 publiée (X.509, base64url). */
    static PublicKey publicKey(String encoded) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                    Codec.fromBase64Url(encoded, "Clé publique")));
        } catch (GeneralSecurityException | ProtocolException e) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Clé publique Ed25519 invalide");
        }
    }
}
