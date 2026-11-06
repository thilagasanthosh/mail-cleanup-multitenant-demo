package dev.demo.mailcleanup.provider;

import dev.demo.mailcleanup.model.ProviderType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ProviderRegistry {
    private final Map<ProviderType, MailboxProvider> providers = new EnumMap<>(ProviderType.class);

    public ProviderRegistry(List<MailboxProvider> all) {
        all.forEach(p -> providers.put(p.type(), p));
    }

    public MailboxProvider forType(ProviderType type) {
        MailboxProvider p = providers.get(type);
        if (p == null) throw new IllegalStateException("No provider for " + type);
        return p;
    }
}
