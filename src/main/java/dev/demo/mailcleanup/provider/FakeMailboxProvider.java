package dev.demo.mailcleanup.provider;

import dev.demo.mailcleanup.config.DemoProperties;
import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.model.FoundMessage;
import dev.demo.mailcleanup.model.ProviderType;
import dev.demo.mailcleanup.model.SearchCriteria;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline stand-in for Graph / Gmail so the demo runs with no accounts.
 * Mailboxes are keyed by tenant + mailbox: tenants never share state.
 * Simulates latency, and one 429 on user5 of any large tenant to show Retry-After handling.
 */
@Component
public class FakeMailboxProvider implements MailboxProvider {

    public static final String PHISH_SENDER = "billing@micros0ft-support.example";
    public static final String PHISH_SUBJECT = "Urgent: invoice overdue - action required";

    private record Msg(String id, String from, String subject, String folder) { }

    private final Map<String, List<Msg>> boxes = new ConcurrentHashMap<>();
    private final Set<String> throttledOnce = ConcurrentHashMap.newKeySet();
    private final AtomicInteger ids = new AtomicInteger();
    private final long latencyMs;

    public FakeMailboxProvider(DemoProperties props) {
        this.latencyMs = props.fakeLatencyMs();
    }

    @Override public ProviderType type() { return ProviderType.FAKE; }

    @Override
    public List<FoundMessage> find(TenantConfig tenant, String mailbox, SearchCriteria criteria) {
        pause();
        String key = key(tenant, mailbox);
        if (tenant.mailboxes().size() > 10 && mailbox.startsWith("user5@") && throttledOnce.add(key)) {
            throw new ThrottledException(Duration.ofSeconds(1));
        }
        synchronized (box(tenant, mailbox)) {
            return box(tenant, mailbox).stream()
                    .filter(m -> m.folder().equals("inbox"))
                    .filter(m -> criteria.isEmpty() || criteria.matches(m.from(), m.subject()))
                    .map(m -> new FoundMessage(m.id(), m.from(), m.subject()))
                    .toList();
        }
    }

    @Override
    public String quarantine(TenantConfig tenant, String mailbox, String messageId) {
        pause();
        return move(tenant, mailbox, messageId, "quarantine");
    }

    @Override
    public void restore(TenantConfig tenant, String mailbox, String quarantinedId) {
        pause();
        move(tenant, mailbox, quarantinedId, "inbox");
    }

    private String move(TenantConfig tenant, String mailbox, String id, String folder) {
        List<Msg> box = box(tenant, mailbox);
        synchronized (box) {
            for (int i = 0; i < box.size(); i++) {
                Msg m = box.get(i);
                if (m.id().equals(id)) {
                    box.set(i, new Msg(m.id(), m.from(), m.subject(), folder));
                    return m.id();
                }
            }
        }
        throw new IllegalStateException("Message " + id + " not found in " + mailbox);
    }

    private List<Msg> box(TenantConfig tenant, String mailbox) {
        return boxes.computeIfAbsent(key(tenant, mailbox), k -> seed(tenant, mailbox));
    }

    /** Every mailbox gets normal mail; most also received the phishing campaign. */
    private List<Msg> seed(TenantConfig tenant, String mailbox) {
        List<Msg> list = new ArrayList<>();
        list.add(new Msg(nextId(), "it-team@" + tenant.id() + ".example", "Laptop refresh schedule", "inbox"));
        list.add(new Msg(nextId(), "news@vendor.example", "Monthly product newsletter", "inbox"));
        int index = tenant.mailboxes().indexOf(mailbox) + 1;
        if (index % 4 != 0) { // every 4th mailbox did not receive the campaign
            list.add(new Msg(nextId(), PHISH_SENDER, PHISH_SUBJECT, "inbox"));
        }
        return list;
    }

    private String nextId() { return "msg-" + ids.incrementAndGet(); }

    private static String key(TenantConfig t, String mailbox) { return t.id() + "|" + mailbox; }

    private void pause() {
        if (latencyMs == 0) return;
        try {
            Thread.sleep(latencyMs); // cheap on a virtual thread
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
