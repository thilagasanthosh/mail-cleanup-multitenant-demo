package dev.demo.mailcleanup.web;

import dev.demo.mailcleanup.config.DemoProperties;
import dev.demo.mailcleanup.config.DemoProperties.RateLimit;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerRateLimiterTest {

    /** Clock the test moves by hand. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final CustomerRateLimiter limiter = new CustomerRateLimiter(
            new DemoProperties(4, 100, 0, 3, List.of(), null, new RateLimit(2, 1)), clock);

    @Test
    void allowsBurstUpToCapacityThenAsksToWait() {
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void oneCustomerCannotUseUpAnothersBudget() {
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("b")).isZero();
    }

    @Test
    void refillsOverTime() {
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        clock.now = clock.now.plusSeconds(1);
        assertThat(limiter.tryAcquire("a")).isZero();
    }
}
