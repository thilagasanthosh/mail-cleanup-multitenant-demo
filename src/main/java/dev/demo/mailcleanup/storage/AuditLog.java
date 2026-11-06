package dev.demo.mailcleanup.storage;

import dev.demo.mailcleanup.model.AuditEntry;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Audit trail for one shard, partitioned by month (production: a PostgreSQL table partitioned by range
 * on the timestamp, so old months can be archived or dropped without touching hot data).
 */
public class AuditLog {

    private final Map<YearMonth, List<AuditEntry>> partitions = new ConcurrentSkipListMap<>();

    public void append(AuditEntry entry) {
        List<AuditEntry> partition = partitions.computeIfAbsent(
                YearMonth.from(entry.at().atZone(ZoneOffset.UTC)), m -> new ArrayList<>());
        synchronized (partition) {
            partition.add(entry);
        }
    }

    /** Entries for one tenant only, oldest first. */
    public List<AuditEntry> forTenant(String tenantId) {
        List<AuditEntry> out = new ArrayList<>();
        for (List<AuditEntry> partition : partitions.values()) {
            synchronized (partition) {
                partition.stream().filter(a -> a.tenantId().equals(tenantId)).forEach(out::add);
            }
        }
        return out;
    }

    /** Partition name -> row count, to show how the data is laid out. */
    public Map<String, Integer> partitionSizes() {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        partitions.forEach((month, rows) -> out.put("audit_" + month.toString().replace("-", "_"), rows.size()));
        return out;
    }
}
