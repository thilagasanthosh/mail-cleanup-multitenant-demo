package dev.demo.mailcleanup.config;

import dev.demo.mailcleanup.model.ProviderType;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One customer tenant. Credentials are per tenant: a token for tenant A can never touch tenant B.
 *
 * @param accessToken       demo shortcut: a delegated token (Graph Explorer / Google OAuth Playground)
 * @param azureTenantId     M365 production path: app-only client-credentials flow per customer tenant
 * @param fakeMailboxCount  FAKE only: generate user1..userN mailboxes to simulate a large tenant
 * @param region            data-residency region; the tenant's state lives in a shard in this region
 * @param shard             optional explicit shard from the routing table (otherwise first shard in region)
 * @param distributionLists list address -> member addresses (may contain nested lists)
 * @param forwards          mailbox -> address it auto-forwards to (the forwarded copy must be pulled too)
 * @param ewsUrl            EXCHANGE only: EWS endpoint of the customer's Exchange server
 */
public record TenantConfig(String id, ProviderType provider, int maxConcurrency, List<String> mailboxes,
                           int fakeMailboxCount, String accessToken,
                           String azureTenantId, String clientId, String clientSecret,
                           String region, String shard,
                           Map<String, List<String>> distributionLists, Map<String, String> forwards,
                           String ewsUrl) {

    @ConstructorBinding
    public TenantConfig {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("tenant id is required");
        if (provider == null) provider = ProviderType.FAKE;
        if (maxConcurrency <= 0) maxConcurrency = 2;
        List<String> all = new ArrayList<>(mailboxes == null ? List.of() : mailboxes);
        for (int i = 1; i <= fakeMailboxCount; i++) all.add("user" + i + "@" + id + ".example");
        mailboxes = List.copyOf(all);
        region = region == null || region.isBlank() ? "US" : region.trim().toUpperCase();
        distributionLists = distributionLists == null ? Map.of() : Map.copyOf(distributionLists);
        forwards = forwards == null ? Map.of() : Map.copyOf(forwards);
        if (ewsUrl == null || ewsUrl.isBlank()) ewsUrl = "https://outlook.office365.com/EWS/Exchange.asmx";
    }

    /** Short form used by tests and simple configs. */
    public TenantConfig(String id, ProviderType provider, int maxConcurrency, List<String> mailboxes) {
        this(id, provider, maxConcurrency, mailboxes, 0, null, null, null, null, null, null, null, null, null);
    }

    /** Safe view for APIs: never return secrets. */
    public String describe() {
        return id + " (" + provider + ", " + region + ", " + mailboxes.size() + " mailboxes, max "
                + maxConcurrency + " concurrent)";
    }
}
