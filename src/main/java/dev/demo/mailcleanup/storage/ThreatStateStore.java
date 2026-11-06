package dev.demo.mailcleanup.storage;

import dev.demo.mailcleanup.model.AlertMessage;
import dev.demo.mailcleanup.model.ThreatStatus;
import dev.demo.mailcleanup.storage.IncidentRecord.StatusChange;
import dev.demo.mailcleanup.storage.MessageRecord.MailboxState;
import dev.demo.mailcleanup.storage.MessageRecord.MailboxStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * State store for one shard: message <-> threats and threat status.
 * Production: tables {@code threat(tenant_id, threat_id, status)}, {@code message(tenant_id, message_id)},
 * {@code message_threat(tenant_id, message_id, threat_id)} and {@code mailbox_action(...)}, all keyed by
 * tenant first so a tenant can be moved to another shard. Methods are synchronized: the demo favours
 * correctness over throughput.
 */
public class ThreatStateStore {

    private record Key(String tenantId, String id) { }

    private final Map<Key, IncidentRecord> incidents = new LinkedHashMap<>();
    private final Map<Key, MessageRecord> messages = new LinkedHashMap<>();

    /** Upsert the threat and link it to its messages. Returns the ids of the messages it touches. */
    public synchronized List<String> recordThreat(String tenantId, String threatId, ThreatStatus status,
                                                  List<AlertMessage> alertMessages, Instant now) {
        Key key = new Key(tenantId, threatId);
        IncidentRecord old = incidents.get(key);
        LinkedHashSet<String> ids = new LinkedHashSet<>(old == null ? List.of() : old.messageIds());
        alertMessages.forEach(m -> ids.add(m.messageId()));
        List<StatusChange> history = new ArrayList<>(old == null ? List.of() : old.history());
        if (old == null || old.status() != status) history.add(new StatusChange(now, status));
        incidents.put(key, new IncidentRecord(tenantId, threatId, status, old == null ? now : old.firstSeen(), now,
                ids, List.copyOf(history), old != null && old.awaitingApproval(), old != null && old.approved()));

        for (AlertMessage m : alertMessages) {
            Key mk = new Key(tenantId, m.messageId());
            MessageRecord msg = messages.get(mk);
            if (msg == null) {
                msg = new MessageRecord(tenantId, m.messageId(), m.sender(), m.subject(), m.recipients(),
                        new LinkedHashMap<>(), new LinkedHashMap<>(), now);
                messages.put(mk, msg);
            }
            msg.threats().put(threatId, status);
        }
        // A threat may already be linked to messages not repeated in this alert: update their copy too.
        for (String id : ids) {
            MessageRecord msg = messages.get(new Key(tenantId, id));
            if (msg != null) msg.threats().put(threatId, status);
        }
        return List.copyOf(ids);
    }

    public synchronized MessageRecord message(String tenantId, String messageId) {
        MessageRecord m = messages.get(new Key(tenantId, messageId));
        if (m == null) throw new NoSuchElementException("Unknown message " + messageId + " in tenant " + tenantId);
        return copy(m);
    }

    public synchronized Optional<IncidentRecord> incident(String tenantId, String threatId) {
        return Optional.ofNullable(incidents.get(new Key(tenantId, threatId)));
    }

    public synchronized List<IncidentRecord> incidents(String tenantId) {
        return incidents.values().stream().filter(i -> i.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(IncidentRecord::lastUpdated).reversed()).toList();
    }

    public synchronized List<MessageRecord> messages(String tenantId) {
        return messages.values().stream().filter(m -> m.tenantId().equals(tenantId)).map(ThreatStateStore::copy).toList();
    }

    public synchronized void markAwaitingApproval(String tenantId, String threatId, boolean awaiting) {
        update(tenantId, threatId, i -> new IncidentRecord(i.tenantId(), i.threatId(), i.status(), i.firstSeen(),
                i.lastUpdated(), i.messageIds(), i.history(), awaiting, i.approved()));
    }

    public synchronized void approve(String tenantId, String threatId) {
        update(tenantId, threatId, i -> new IncidentRecord(i.tenantId(), i.threatId(), i.status(), i.firstSeen(),
                i.lastUpdated(), i.messageIds(), i.history(), false, true));
    }

    /** True when every bad threat on this message has been approved by an analyst. */
    public synchronized boolean approvedForLargeAction(String tenantId, String messageId) {
        MessageRecord m = messages.get(new Key(tenantId, messageId));
        if (m == null) return false;
        return m.threats().entrySet().stream().filter(e -> e.getValue().isBad())
                .allMatch(e -> incidents.get(new Key(tenantId, e.getKey())).approved());
    }

    public synchronized void markMailbox(String tenantId, String messageId, String mailbox, MailboxStatus status,
                                         List<String> providerIds, Instant now) {
        MessageRecord m = messages.get(new Key(tenantId, messageId));
        if (m == null) throw new NoSuchElementException("Unknown message " + messageId);
        m.mailboxes().put(mailbox, new MailboxState(status, List.copyOf(providerIds), now));
    }

    public synchronized Collection<String> threatIdsFor(String tenantId, String messageId) {
        return List.copyOf(message(tenantId, messageId).threats().keySet());
    }

    private void update(String tenantId, String threatId, java.util.function.UnaryOperator<IncidentRecord> f) {
        Key key = new Key(tenantId, threatId);
        IncidentRecord i = incidents.get(key);
        if (i == null) throw new NoSuchElementException("Unknown incident " + threatId + " in tenant " + tenantId);
        incidents.put(key, f.apply(i));
    }

    private static MessageRecord copy(MessageRecord m) {
        return new MessageRecord(m.tenantId(), m.messageId(), m.sender(), m.subject(), m.recipients(),
                Map.copyOf(new LinkedHashMap<>(m.threats())), Map.copyOf(new LinkedHashMap<>(m.mailboxes())), m.firstSeen());
    }
}
