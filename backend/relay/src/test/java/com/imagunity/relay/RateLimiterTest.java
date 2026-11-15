package com.imagunity.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Seaux de jetons du relais. */
class RateLimiterTest {

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-15T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final RateLimiter limiter = new RateLimiter(clock);

    @Test
    void aBurstOfOneMinuteThenTheRateItself() {
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.acquire("box:a", 60)).isZero();
        }
        assertThat(limiter.acquire("box:a", 60)).as("seau vide : 1 s à attendre").isEqualTo(1);
        assertThat(limiter.peek("box:a", 60)).isEqualTo(1);
        assertThat(limiter.acquire("box:b", 60)).as("autre seau").isZero();
        clock.now = clock.now.plusSeconds(1);
        assertThat(limiter.peek("box:a", 60)).as("regarder ne prend rien").isZero();
        assertThat(limiter.acquire("box:a", 60)).isZero();
        assertThat(limiter.acquire("box:a", 60)).isEqualTo(1);
        clock.now = clock.now.plusSeconds(3600);
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.acquire("box:a", 60)).as("plafonné à une minute de jetons").isZero();
        }
        assertThat(limiter.acquire("box:a", 60)).isEqualTo(1);
        assertThat(limiter.acquire("x", 2)).isZero();
        assertThat(limiter.acquire("x", 2)).isZero();
        assertThat(limiter.acquire("x", 2)).as("2 par minute : 30 s").isEqualTo(30);
        assertThat(limiter.acquire("y", 0)).as("limite désactivée").isZero();
    }

    @Test
    void idleBucketsAreForgottenWhenThereAreTooMany() {
        for (int i = 0; i <= RateLimiter.MAX_BUCKETS; i++) {
            limiter.acquire("box:" + i, 10);
        }
        clock.now = clock.now.plusSeconds(120);
        limiter.acquire("box:nouvelle", 10);
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void eachBucketIsJudgedFullAgainstItsOwnLimit() {
        for (int i = 0; i < 100; i++) {
            limiter.acquire("box:active", 600); // seau à 500 jetons sur 600 : pas plein
        }
        for (int i = 0; i <= RateLimiter.MAX_BUCKETS; i++) {
            limiter.acquire("echecs:" + i, 30);
        }
        clock.now = clock.now.plusSeconds(3); // les seaux d'échecs se remplissent ; box:active gagne 30 jetons
        limiter.peek("echecs:nouveau", 30); // oubli jugé avec une limite de 30

        assertThat(limiter.size()).as("box:active, à moitié vide, gardé ; échecs pleins oubliés").isEqualTo(2);
    }
}
