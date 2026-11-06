package dev.demo.mailcleanup.config;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@Component
public class TenantRegistry {
    private final Map<String, TenantConfig> tenants = new LinkedHashMap<>();

    public TenantRegistry(DemoProperties props) {
        props.tenants().forEach(t -> tenants.put(t.id(), t));
    }

    public TenantConfig get(String tenantId) {
        TenantConfig t = tenants.get(tenantId);
        if (t == null) throw new NoSuchElementException("Unknown tenant: " + tenantId);
        return t;
    }

    public Collection<TenantConfig> all() { return tenants.values(); }
}
