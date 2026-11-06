package dev.demo.mailcleanup.web;

import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.config.TenantRegistry;
import dev.demo.mailcleanup.intake.IntakeQueue;
import dev.demo.mailcleanup.metrics.TenantMetrics;
import dev.demo.mailcleanup.service.DeadLetterQueue;
import dev.demo.mailcleanup.service.DeadLetterQueue.DeadLetter;
import dev.demo.mailcleanup.service.TenantFairDispatcher;
import dev.demo.mailcleanup.storage.Shard;
import dev.demo.mailcleanup.storage.ShardRouter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Metrics per customer, dead-letter queue (alert + replay) and the shard routing table. */
@RestController
@RequestMapping("/api")
public class OperationsController {

    private final TenantRegistry tenants;
    private final TenantMetrics metrics;
    private final IntakeQueue intake;
    private final TenantFairDispatcher dispatcher;
    private final DeadLetterQueue deadLetters;
    private final ShardRouter shards;

    public OperationsController(TenantRegistry tenants, TenantMetrics metrics, IntakeQueue intake,
                                TenantFairDispatcher dispatcher, DeadLetterQueue deadLetters, ShardRouter shards) {
        this.tenants = tenants;
        this.metrics = metrics;
        this.intake = intake;
        this.dispatcher = dispatcher;
        this.deadLetters = deadLetters;
        this.shards = shards;
    }

    /** Queue age, p95 time to quarantine and 429 rate for ONE customer. */
    @GetMapping("/tenants/{tenantId}/metrics")
    public Map<String, Object> tenantMetrics(@PathVariable String tenantId) {
        TenantConfig tenant = tenants.get(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intakeQueueDepth", intake.depth(tenant.id()));
        out.put("intakeOldestAgeMs", intake.oldestAgeMs(tenant.id()));
        out.put("actionQueueDepth", dispatcher.queuedFor(tenant.id()));
        out.put("deadLetters", deadLetters.size(tenant.id()));
        out.putAll(metrics.snapshot(tenant.id()));
        return out;
    }

    /** Operator view across all customers (not under /tenants, so not per-customer rate limited). */
    @GetMapping("/metrics")
    public Map<String, Object> allMetrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        tenants.all().forEach(t -> out.put(t.id(), tenantMetrics(t.id())));
        out.put("_lanes", dispatcher.stats());
        return out;
    }

    @GetMapping("/tenants/{tenantId}/dead-letters")
    public List<DeadLetter> deadLetters(@PathVariable String tenantId) {
        tenants.get(tenantId);
        return deadLetters.forTenant(tenantId);
    }

    @PostMapping("/tenants/{tenantId}/dead-letters/{id}/replay")
    public DeadLetter replay(@PathVariable String tenantId, @PathVariable String id) {
        tenants.get(tenantId);
        return deadLetters.replay(tenantId, id);
    }

    /** Routing table: customer -> database shard, and the region each shard lives in. */
    @GetMapping("/routing")
    public Map<String, Object> routing() {
        Map<String, Object> shardView = new LinkedHashMap<>();
        for (Shard s : shards.shards().values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList()) {
            shardView.put(s.id(), Map.of("region", s.region(), "auditPartitions", s.audit().partitionSizes()));
        }
        return Map.of("customers", shards.routingTable(), "shards", shardView);
    }
}
