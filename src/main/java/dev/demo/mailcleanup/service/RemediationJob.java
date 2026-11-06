package dev.demo.mailcleanup.service;

import dev.demo.mailcleanup.model.MailboxResult;
import dev.demo.mailcleanup.model.SearchCriteria;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One "pull this campaign" job. Results are stored per (tenant, mailbox). */
public class RemediationJob {
    private final String id;
    private final SearchCriteria criteria;
    private final Instant startedAt = Instant.now();
    private final long startNanos = System.nanoTime();
    private final Map<String, Map<String, MailboxResult>> results = new ConcurrentHashMap<>();

    public RemediationJob(String id, SearchCriteria criteria) {
        this.id = id;
        this.criteria = criteria;
    }

    public String id() { return id; }
    public SearchCriteria criteria() { return criteria; }
    public Instant startedAt() { return startedAt; }
    public long elapsedMs() { return (System.nanoTime() - startNanos) / 1_000_000; }

    public void put(MailboxResult r) {
        results.computeIfAbsent(r.tenantId(), t -> new ConcurrentHashMap<>()).put(r.mailbox(), r);
    }

    public Map<String, MailboxResult> forTenant(String tenantId) {
        return results.getOrDefault(tenantId, Map.of());
    }

    public List<String> tenantIds() { return List.copyOf(results.keySet()); }
}
