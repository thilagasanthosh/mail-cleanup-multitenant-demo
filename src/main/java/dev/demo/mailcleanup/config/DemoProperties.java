package dev.demo.mailcleanup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;

/**
 * @param globalMaxConcurrency total mailbox calls in flight across all tenants (protects our service)
 * @param approvalThreshold    guardrail: actions touching more mailboxes than this need explicit approval
 * @param fakeLatencyMs        simulated provider latency for the offline FAKE provider
 * @param actionMaxAttempts    attempts per mailbox action before it goes to the dead-letter queue
 * @param shards               routing table targets: one relational database per shard, each in a region
 * @param apiRateLimit         token bucket per customer for the customer-facing APIs
 */
@ConfigurationProperties(prefix = "demo")
public record DemoProperties(int globalMaxConcurrency, int approvalThreshold, long fakeLatencyMs,
                             int actionMaxAttempts, List<TenantConfig> tenants, List<ShardConfig> shards,
                             RateLimit apiRateLimit) {

    /** Token bucket per customer: {@code capacity} burst, refilled at {@code refillPerSecond}. */
    public record RateLimit(int capacity, int refillPerSecond) {
        public RateLimit {
            if (capacity <= 0) capacity = 50;
            if (refillPerSecond <= 0) refillPerSecond = 20;
        }
    }

    @ConstructorBinding
    public DemoProperties {
        if (globalMaxConcurrency <= 0) globalMaxConcurrency = 4;
        if (approvalThreshold <= 0) approvalThreshold = 100;
        if (fakeLatencyMs < 0) fakeLatencyMs = 0;
        if (actionMaxAttempts <= 0) actionMaxAttempts = 3;
        tenants = tenants == null ? List.of() : List.copyOf(tenants);
        shards = shards == null || shards.isEmpty() ? List.of(new ShardConfig("DB1", "US")) : List.copyOf(shards);
        if (apiRateLimit == null) apiRateLimit = new RateLimit(0, 0);
    }

    /** Convenience for tests. */
    public DemoProperties(int globalMaxConcurrency, int approvalThreshold, long fakeLatencyMs, List<TenantConfig> tenants) {
        this(globalMaxConcurrency, approvalThreshold, fakeLatencyMs, 0, tenants, null, null);
    }
}
