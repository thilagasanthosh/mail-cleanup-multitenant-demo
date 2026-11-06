package dev.demo.mailcleanup.metrics;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-customer operational metrics: time to quarantine (p95), provider calls and 429 rate,
 * dead letters. Queue depth and age come from the queues themselves. Production: Micrometer
 * timers/counters tagged with the tenant id, scraped by Prometheus.
 */
@Component
public class TenantMetrics {

    private static final int WINDOW = 1000;

    private static final class Counters {
        final AtomicLong providerCalls = new AtomicLong();
        final AtomicLong throttled = new AtomicLong();
        final AtomicLong quarantined = new AtomicLong();
        final AtomicLong restored = new AtomicLong();
        final AtomicLong deadLettered = new AtomicLong();
        final Deque<Long> timeToQuarantineMs = new ArrayDeque<>();
    }

    private final Map<String, Counters> byTenant = new ConcurrentHashMap<>();

    public void providerCall(String tenantId) { c(tenantId).providerCalls.incrementAndGet(); }
    public void throttled(String tenantId) { c(tenantId).throttled.incrementAndGet(); }
    public void restored(String tenantId) { c(tenantId).restored.incrementAndGet(); }
    public void deadLettered(String tenantId) { c(tenantId).deadLettered.incrementAndGet(); }

    /** From the alert entering the intake queue to the message being out of the inbox. */
    public void quarantined(String tenantId, Duration sinceAlert) {
        Counters c = c(tenantId);
        c.quarantined.incrementAndGet();
        if (sinceAlert == null) return;
        synchronized (c.timeToQuarantineMs) {
            c.timeToQuarantineMs.addLast(sinceAlert.toMillis());
            if (c.timeToQuarantineMs.size() > WINDOW) c.timeToQuarantineMs.removeFirst();
        }
    }

    public Map<String, Object> snapshot(String tenantId) {
        Counters c = c(tenantId);
        long calls = c.providerCalls.get();
        long throttled = c.throttled.get();
        return Map.of(
                "providerCalls", calls,
                "throttled429", throttled,
                "rate429", calls == 0 ? 0.0 : Math.round(1000.0 * throttled / calls) / 1000.0,
                "messagesQuarantined", c.quarantined.get(),
                "messagesRestored", c.restored.get(),
                "deadLettered", c.deadLettered.get(),
                "p95TimeToQuarantineMs", p95(c));
    }

    static long p95(List<Long> samples) {
        if (samples.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(null);
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static long p95(Counters c) {
        synchronized (c.timeToQuarantineMs) {
            return p95(List.copyOf(c.timeToQuarantineMs));
        }
    }

    private Counters c(String tenantId) { return byTenant.computeIfAbsent(tenantId, t -> new Counters()); }
}
