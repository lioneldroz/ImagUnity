package com.imagunity.relay;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limites de débit par seau de jetons, en mémoire (une instance du relais ; derrière un répartiteur, chaque instance
 * applique la limite pour son compte). Un seau se remplit au rythme de {@code perMinute} jetons par minute, jusqu'à
 * {@code perMinute} jetons : une rafale d'une minute est permise, pas plus.
 */
public final class RateLimiter {

    /** Seaux gardés au plus ; au-delà, les seaux pleins (inactifs) sont oubliés. */
    static final int MAX_BUCKETS = 100_000;

    private record Bucket(double tokens, Instant at) {
    }

    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Prend un jeton du seau {@code key}.
     *
     * @return 0 si le jeton est pris ; sinon le nombre de secondes à attendre avant le prochain jeton
     */
    public long acquire(String key, int perMinute) {
        return take(key, perMinute, true);
    }

    /** Comme {@link #acquire}, sans prendre de jeton : secondes à attendre si le seau est vide, 0 sinon. */
    public long peek(String key, int perMinute) {
        return take(key, perMinute, false);
    }

    private long take(String key, int perMinute, boolean consume) {
        if (perMinute <= 0) {
            return 0; // limite désactivée
        }
        if (buckets.size() > MAX_BUCKETS) {
            forgetFull(perMinute);
        }
        Instant now = clock.instant();
        long[] wait = {0};
        buckets.compute(key, (k, b) -> {
            double tokens = b == null ? perMinute
                    : Math.min(perMinute, b.tokens() + (now.toEpochMilli() - b.at().toEpochMilli()) * perMinute / 60_000.0);
            if (tokens >= 1) {
                return new Bucket(consume ? tokens - 1 : tokens, now);
            }
            wait[0] = Math.max(1, (long) Math.ceil((1 - tokens) * 60.0 / perMinute));
            return new Bucket(tokens, now);
        });
        return wait[0];
    }

    private void forgetFull(int perMinute) {
        Instant now = clock.instant();
        buckets.entrySet().removeIf(e -> e.getValue().tokens()
                + (now.toEpochMilli() - e.getValue().at().toEpochMilli()) * perMinute / 60_000.0 >= perMinute);
    }

    int size() {
        return buckets.size();
    }
}
