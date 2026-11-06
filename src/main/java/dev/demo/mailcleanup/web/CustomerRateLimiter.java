package dev.demo.mailcleanup.web;

import dev.demo.mailcleanup.config.DemoProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket per customer for the customer-facing APIs: one customer's script hammering the API
 * cannot starve the others. Production: the same rule in the API gateway, backed by Redis.
 */
@Component
public class CustomerRateLimiter {

    private final class Bucket {
        double tokens = capacity;
        long lastRefillMs = clock.millis();
    }

    private final Clock clock;
    private final int capacity;
    private final int refillPerSecond;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public CustomerRateLimiter(DemoProperties props, Clock clock) {
        this.clock = clock;
        this.capacity = props.apiRateLimit().capacity();
        this.refillPerSecond = props.apiRateLimit().refillPerSecond();
    }

    /** @return {@link Duration#ZERO} when allowed, otherwise how long until a token is available. */
    public Duration tryAcquire(String customerId) {
        Bucket b = buckets.computeIfAbsent(customerId, c -> new Bucket());
        synchronized (b) {
            long now = clock.millis();
            b.tokens = Math.min(capacity, b.tokens + (now - b.lastRefillMs) * refillPerSecond / 1000.0);
            b.lastRefillMs = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return Duration.ZERO;
            }
            return Duration.ofMillis((long) Math.ceil((1 - b.tokens) * 1000.0 / refillPerSecond));
        }
    }
}
