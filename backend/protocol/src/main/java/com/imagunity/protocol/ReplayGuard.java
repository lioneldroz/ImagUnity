package com.imagunity.protocol;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Anti-rejeu côté relais (spécification § 6.3) : une requête est refusée si son horodatage s'écarte de plus de la
 * fenêtre (5 minutes par défaut), ou si son identifiant a déjà été vu dans la fenêtre. Mémoire bornée : au-delà de
 * {@code capacity} identifiants récents, les requêtes sont refusées plutôt que d'oublier un identifiant encore valide.
 */
public final class ReplayGuard {

    /** Fenêtre par défaut. */
    public static final Duration WINDOW = Duration.ofMinutes(5);

    private record Seen(String requestId, long expiresAt) {
    }

    private final Clock clock;
    private final long windowSeconds;
    private final int capacity;
    private final Deque<Seen> order = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();

    public ReplayGuard(Clock clock) {
        this(clock, WINDOW, 1_000_000);
    }

    ReplayGuard(Clock clock, Duration window, int capacity) {
        this.clock = clock;
        this.windowSeconds = window.toSeconds();
        this.capacity = capacity;
    }

    /**
     * Enregistre la requête si elle est admise.
     *
     * @return la raison du refus, ou vide si la requête est admise
     */
    public synchronized Optional<String> admit(SignedRequest request) {
        long now = clock.instant().getEpochSecond();
        while (!order.isEmpty() && order.peekFirst().expiresAt() < now) {
            seen.remove(order.pollFirst().requestId());
        }
        if (Math.abs(now - request.timestamp()) > windowSeconds) {
            return Optional.of("horodatage hors de la fenêtre admise (horloge de la box à vérifier)");
        }
        if (seen.contains(request.requestId())) {
            return Optional.of("requête rejouée");
        }
        if (seen.size() >= capacity) {
            return Optional.of("trop de requêtes récentes, réessayer plus tard");
        }
        seen.add(request.requestId());
        // Gardé jusqu'à ce que son horodatage sorte de la fenêtre.
        order.addLast(new Seen(request.requestId(), Math.max(now, request.timestamp()) + windowSeconds));
        return Optional.empty();
    }
}
