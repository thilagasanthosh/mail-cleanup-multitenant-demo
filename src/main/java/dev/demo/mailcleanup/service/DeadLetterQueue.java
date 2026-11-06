package dev.demo.mailcleanup.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Actions that still failed after every retry. Nothing is dropped silently: each one raises an alert
 * (here an ERROR log line, production a page/Slack alert) and can be replayed once the cause is fixed.
 */
@Component
public class DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueue.class);

    /** What the API shows. The replayable task itself stays inside the queue. */
    public record DeadLetter(String id, String tenantId, String mailbox, String action, String reference,
                             int attempts, String reason, Instant at) { }

    private record Entry(DeadLetter letter, Runnable replay) { }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized DeadLetter add(String tenantId, String mailbox, String action, String reference,
                                       int attempts, String reason, Instant at, Runnable replay) {
        DeadLetter letter = new DeadLetter(UUID.randomUUID().toString().substring(0, 8), tenantId, mailbox,
                action, reference, attempts, reason, at);
        entries.put(letter.id(), new Entry(letter, replay));
        log.error("ALERT dead-letter id={} tenant={} mailbox={} action={} ref={} attempts={} reason={}",
                letter.id(), tenantId, mailbox, action, reference, attempts, reason);
        return letter;
    }

    public synchronized List<DeadLetter> forTenant(String tenantId) {
        List<DeadLetter> out = new ArrayList<>();
        entries.values().forEach(e -> { if (e.letter().tenantId().equals(tenantId)) out.add(e.letter()); });
        return out;
    }

    public synchronized int size(String tenantId) { return forTenant(tenantId).size(); }

    /** Remove from the queue and run the action again with a fresh retry budget. */
    public DeadLetter replay(String tenantId, String id) {
        Entry e;
        synchronized (this) {
            e = entries.get(id);
            if (e == null || !e.letter().tenantId().equals(tenantId)) {
                throw new NoSuchElementException("Unknown dead letter " + id + " for tenant " + tenantId);
            }
            entries.remove(id);
        }
        log.info("REPLAY dead-letter id={} tenant={} mailbox={}", id, tenantId, e.letter().mailbox());
        e.replay().run();
        return e.letter();
    }
}
