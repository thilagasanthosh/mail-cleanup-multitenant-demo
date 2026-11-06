package dev.demo.mailcleanup.threat;

import dev.demo.mailcleanup.config.TenantConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Who actually has a copy? Envelope recipients, expanded through distribution lists (nested) and
 * mailbox forwarding, limited to mailboxes that belong to this tenant. Cycle safe.
 * Production: directory lookups (Graph groups/members, Admin SDK) plus message-trace for forwards.
 */
public final class RecipientResolver {

    private RecipientResolver() { }

    public static Set<String> resolve(TenantConfig tenant, List<String> recipients) {
        Set<String> owned = new HashSet<>();
        tenant.mailboxes().forEach(m -> owned.add(lower(m)));

        Set<String> visited = new HashSet<>();
        Set<String> mailboxes = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        recipients.forEach(r -> todo.add(lower(r)));

        while (!todo.isEmpty()) {
            String address = todo.poll();
            if (!visited.add(address)) continue;
            List<String> members = listMembers(tenant, address);
            if (members != null) {
                members.forEach(m -> todo.add(lower(m)));
                continue;
            }
            if (owned.contains(address)) mailboxes.add(original(tenant, address));
            String forward = forwardOf(tenant, address);
            if (forward != null) todo.add(lower(forward));
        }
        return mailboxes;
    }

    private static List<String> listMembers(TenantConfig t, String address) {
        return t.distributionLists().entrySet().stream()
                .filter(e -> lower(e.getKey()).equals(address)).map(java.util.Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static String forwardOf(TenantConfig t, String address) {
        return t.forwards().entrySet().stream()
                .filter(e -> lower(e.getKey()).equals(address)).map(java.util.Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /** Keep the mailbox spelling from config: it is what the provider expects. */
    private static String original(TenantConfig t, String address) {
        return t.mailboxes().stream().filter(m -> lower(m).equals(address)).findFirst().orElse(address);
    }

    private static String lower(String s) { return s.trim().toLowerCase(Locale.ROOT); }
}
