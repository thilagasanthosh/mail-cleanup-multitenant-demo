package dev.demo.mailcleanup.storage;

import dev.demo.mailcleanup.config.DemoProperties;
import dev.demo.mailcleanup.config.ShardConfig;
import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.config.TenantRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Routing table: customer -> database. Shard by customer so one huge customer cannot grow a single
 * database without bound, and so each customer's data stays in its region (US, EU, IN, AU).
 * A tenant names its shard explicitly, or gets the first shard in its region.
 */
@Component
public class ShardRouter {

    public record Route(String tenantId, String shard, String region) { }

    private final Map<String, Shard> shards = new LinkedHashMap<>();
    private final Map<String, Route> routes = new LinkedHashMap<>();

    public ShardRouter(DemoProperties props, TenantRegistry tenants) {
        for (ShardConfig s : props.shards()) {
            shards.put(s.id(), new Shard(s.id(), s.region(), new ThreatStateStore(), new AuditLog()));
        }
        for (TenantConfig t : tenants.all()) {
            Shard shard = pick(t);
            routes.put(t.id(), new Route(t.id(), shard.id(), shard.region()));
        }
    }

    private Shard pick(TenantConfig t) {
        if (t.shard() != null && !t.shard().isBlank()) {
            Shard s = shards.get(t.shard());
            if (s == null) throw new IllegalStateException("Tenant " + t.id() + " routes to unknown shard " + t.shard());
            if (!s.region().equals(t.region())) {
                throw new IllegalStateException("Tenant " + t.id() + " is in region " + t.region()
                        + " but shard " + s.id() + " is in " + s.region() + " (data residency)");
            }
            return s;
        }
        return shards.values().stream().filter(s -> s.region().equals(t.region())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No shard in region " + t.region() + " for tenant " + t.id()));
    }

    public Shard forTenant(String tenantId) {
        Route r = routes.get(tenantId);
        if (r == null) throw new NoSuchElementException("Unknown tenant: " + tenantId);
        return shards.get(r.shard());
    }

    public Map<String, Route> routingTable() { return Map.copyOf(routes); }

    public Map<String, Shard> shards() { return Map.copyOf(shards); }
}
