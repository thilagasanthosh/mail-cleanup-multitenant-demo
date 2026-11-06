package dev.demo.mailcleanup.provider;

import java.time.Duration;

/** Provider said 429 Too Many Requests. Carries Retry-After so we back off politely. */
public class ThrottledException extends RuntimeException {
    private final Duration retryAfter;

    public ThrottledException(Duration retryAfter) {
        super("Throttled by provider, retry after " + retryAfter.toMillis() + " ms");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() { return retryAfter; }

    static Duration parseRetryAfter(String header) {
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(header.trim())));
        } catch (Exception e) {
            return Duration.ofSeconds(2);
        }
    }
}
