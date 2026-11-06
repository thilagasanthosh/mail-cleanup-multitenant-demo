package dev.demo.mailcleanup.threat;

import dev.demo.mailcleanup.config.DemoProperties;
import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.config.TenantRegistry;
import dev.demo.mailcleanup.intake.AlertNormalizer;
import dev.demo.mailcleanup.intake.IntakeQueue;
import dev.demo.mailcleanup.intake.IntakeQueue.Delivery;
import dev.demo.mailcleanup.metrics.TenantMetrics;
import dev.demo.mailcleanup.model.AuditEntry;
import dev.demo.mailcleanup.model.Decision;
import dev.demo.mailcleanup.model.FoundMessage;
import dev.demo.mailcleanup.model.SearchCriteria;
import dev.demo.mailcleanup.model.ThreatAlert;
import dev.demo.mailcleanup.model.ThreatStatus;
import dev.demo.mailcleanup.service.ActionRunner;
import dev.demo.mailcleanup.service.ActionRunner.ActionTask;
import dev.demo.mailcleanup.service.ActionRunner.Outcome;
import dev.demo.mailcleanup.service.DeadLetterQueue;
import dev.demo.mailcleanup.storage.IncidentRecord;
import dev.demo.mailcleanup.storage.MessageRecord;
import dev.demo.mailcleanup.storage.MessageRecord.MailboxState;
import dev.demo.mailcleanup.storage.MessageRecord.MailboxStatus;
import dev.demo.mailcleanup.storage.ShardRouter;
import dev.demo.mailcleanup.storage.ThreatStateStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alert-driven pipeline (the top row of the design, then down into the action row):
 * <pre>
 *  threat alert -> intake queue (fair per customer) -> normalise + dedup -> state store (message <-> threats)
 *            -> decision (any bad -> quarantine, all cleared -> restore) -> resolve recipients
 *            -> action lanes (customer x provider) -> mailbox connector -> quarantine / restore
 * </pre>
 * Alerts are per threat; actions are per message and per mailbox.
 */
@Service
public class ThreatResponseService {

    private static final Logger log = LoggerFactory.getLogger(ThreatResponseService.class);
    private static final int MAX_INTAKE_DELIVERIES = 3;
    private static final String ACTOR = "threat-response";

    private final IntakeQueue intake;
    private final AlertNormalizer normalizer;
    private final TenantRegistry tenants;
    private final ShardRouter shards;
    private final ActionRunner runner;
    private final DeadLetterQueue deadLetters;
    private final TenantMetrics metrics;
    private final DemoProperties props;
    private final Clock clock;

    /** (tenant|message|mailbox) with an action in flight: never run two actions on one copy at once. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;
    private Thread consumer;

    public ThreatResponseService(IntakeQueue intake, AlertNormalizer normalizer, TenantRegistry tenants,
                                 ShardRouter shards, ActionRunner runner, DeadLetterQueue deadLetters,
                                 TenantMetrics metrics, DemoProperties props, Clock clock) {
        this.intake = intake;
        this.normalizer = normalizer;
        this.tenants = tenants;
        this.shards = shards;
        this.runner = runner;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void startConsumer() {
        consumer = Thread.ofVirtual().name("intake-consumer").start(this::consume);
    }

    @PreDestroy
    void stopConsumer() {
        running = false;
        if (consumer != null) consumer.interrupt();
    }

    /** Entry point for the detection stack. Only enqueues: the caller is never slowed by mailbox work. */
    public int submit(List<ThreatAlert> alerts) {
        for (ThreatAlert a : alerts) {
            if (a == null || a.tenantId() == null) throw new IllegalArgumentException("tenantId is required");
            tenants.get(a.tenantId().trim());
        }
        alerts.forEach(intake::offer);
        return alerts.size();
    }

    private void consume() {
        while (running) {
            Optional<Delivery> next;
            try {
                next = intake.poll(Duration.ofMillis(500));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            next.ifPresent(this::handle);
        }
    }

    private void handle(Delivery d) {
        try {
            process(d);
            intake.ack(d); // acknowledge only after state is stored and actions are queued
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (d.deliveries() < MAX_INTAKE_DELIVERIES) {
                log.warn("INTAKE redeliver threat={} delivery={}: {}", d.alert().threatId(), d.deliveries(), reason);
                intake.nack(d);
            } else {
                intake.ack(d);
                metrics.deadLettered(d.alert().tenantId());
                deadLetters.add(d.alert().tenantId(), "-", "INTAKE", d.alert().threatId(), d.deliveries(), reason,
                        clock.instant(), () -> intake.offer(d.alert()));
            }
        }
    }

    void process(Delivery d) {
        Optional<ThreatAlert> normalised = normalizer.normaliseAndDedup(d.alert());
        if (normalised.isEmpty()) {
            log.info("DEDUP tenant={} threat={} status={} (already processed)",
                    d.alert().tenantId(), d.alert().threatId(), d.alert().status());
            return;
        }
        ThreatAlert alert = normalised.get();
        TenantConfig tenant = tenants.get(alert.tenantId());
        ThreatStateStore store = state(tenant.id());
        List<String> messageIds = store.recordThreat(tenant.id(), alert.threatId(), alert.status(), alert.messages(),
                clock.instant());
        audit(tenant.id(), "-", "THREAT_" + alert.status(), alert.threatId());
        log.info("ALERT tenant={} threat={} status={} messages={}", tenant.id(), alert.threatId(), alert.status(),
                messageIds.size());
        for (String messageId : messageIds) evaluate(tenant, messageId, d.enqueuedAt());
    }

    /** Decide per mailbox copy of the message and queue the actions. */
    private void evaluate(TenantConfig tenant, String messageId, Instant alertAt) {
        ThreatStateStore store = state(tenant.id());
        MessageRecord msg = store.message(tenant.id(), messageId);
        Set<String> mailboxes = RecipientResolver.resolve(tenant, msg.recipients());

        List<String> toQuarantine = new ArrayList<>();
        List<String> toRestore = new ArrayList<>();
        for (String mailbox : mailboxes) {
            MailboxState s = msg.mailboxes().get(mailbox);
            if (s != null && s.status() == MailboxStatus.NOT_FOUND) continue;
            boolean quarantined = s != null && s.status() == MailboxStatus.QUARANTINED;
            Decision decision = RemediationDecider.decide(msg.threats().values(), quarantined);
            if (decision == Decision.QUARANTINE) toQuarantine.add(mailbox);
            if (decision == Decision.RESTORE) toRestore.add(mailbox);
        }

        // Safety: a very large action waits for an analyst (a bad verdict must not empty 10K inboxes unchecked).
        if (toQuarantine.size() > props.approvalThreshold() && !store.approvedForLargeAction(tenant.id(), messageId)) {
            msg.threats().forEach((threatId, status) -> {
                if (status.isBad()) store.markAwaitingApproval(tenant.id(), threatId, true);
            });
            log.warn("HOLD  tenant={} message={} needs approval: {} mailboxes > threshold {}",
                    tenant.id(), messageId, toQuarantine.size(), props.approvalThreshold());
            toQuarantine.clear();
        }

        toQuarantine.forEach(m -> submit(tenant, msg, m, Decision.QUARANTINE, alertAt));
        toRestore.forEach(m -> submit(tenant, msg, m, Decision.RESTORE, alertAt));
    }

    private void submit(TenantConfig tenant, MessageRecord msg, String mailbox, Decision decision, Instant alertAt) {
        String key = tenant.id() + "|" + msg.messageId() + "|" + mailbox;
        if (!inFlight.add(key)) return; // its completion re-evaluates, so the latest verdict still wins
        ActionTask task = decision == Decision.QUARANTINE
                ? quarantineTask(tenant, msg, mailbox, key, alertAt)
                : restoreTask(tenant, msg, mailbox, key, alertAt);
        runner.submit(task);
    }

    private ActionTask quarantineTask(TenantConfig tenant, MessageRecord msg, String mailbox, String key, Instant alertAt) {
        List<String> ids = new ArrayList<>();
        SearchCriteria criteria = new SearchCriteria(msg.sender(), msg.subject());
        return new ActionTask(tenant, mailbox, "QUARANTINE", msg.messageId(),
                (provider, t, m) -> {
                    for (FoundMessage found : provider.find(t, m, criteria)) {
                        String newId = provider.quarantine(t, m, found.id());
                        ids.add(newId);
                        audit(t.id(), m, "QUARANTINE", newId);
                    }
                    if (ids.isEmpty()) {
                        state(t.id()).markMailbox(t.id(), msg.messageId(), m, MailboxStatus.NOT_FOUND, ids, clock.instant());
                        return;
                    }
                    state(t.id()).markMailbox(t.id(), msg.messageId(), m, MailboxStatus.QUARANTINED, ids, clock.instant());
                    metrics.quarantined(t.id(), Duration.between(alertAt, clock.instant()));
                },
                completion(tenant, msg.messageId(), key, alertAt));
    }

    private ActionTask restoreTask(TenantConfig tenant, MessageRecord msg, String mailbox, String key, Instant alertAt) {
        return new ActionTask(tenant, mailbox, "RESTORE", msg.messageId(),
                (provider, t, m) -> {
                    MailboxState s = state(t.id()).message(t.id(), msg.messageId()).mailboxes().get(m);
                    if (s == null || s.status() != MailboxStatus.QUARANTINED) return;
                    for (String id : s.providerIds()) {
                        provider.restore(t, m, id);
                        audit(t.id(), m, "RESTORE", id);
                    }
                    state(t.id()).markMailbox(t.id(), msg.messageId(), m, MailboxStatus.RESTORED, List.of(), clock.instant());
                    metrics.restored(t.id());
                },
                completion(tenant, msg.messageId(), key, alertAt));
    }

    /** When an action finishes, re-check: the verdict may have changed while it was running. */
    private Outcome completion(TenantConfig tenant, String messageId, String key, Instant alertAt) {
        return new Outcome() {
            @Override public void succeeded(int attempts) {
                inFlight.remove(key);
                evaluate(tenant, messageId, alertAt);
            }
            @Override public void failed(int attempts, String reason) {
                inFlight.remove(key); // the dead-letter queue holds it for replay
            }
        };
    }

    /** Analyst approves a large action that was held. */
    public IncidentRecord approve(String tenantId, String threatId) {
        TenantConfig tenant = tenants.get(tenantId);
        ThreatStateStore store = state(tenantId);
        IncidentRecord incident = incident(tenantId, threatId);
        store.approve(tenantId, threatId);
        audit(tenantId, "-", "APPROVE", threatId);
        incident.messageIds().forEach(id -> evaluate(tenant, id, clock.instant()));
        return incident(tenantId, threatId);
    }

    public List<IncidentRecord> incidents(String tenantId) {
        tenants.get(tenantId);
        return state(tenantId).incidents(tenantId);
    }

    public IncidentRecord incident(String tenantId, String threatId) {
        tenants.get(tenantId);
        return state(tenantId).incident(tenantId, threatId)
                .orElseThrow(() -> new NoSuchElementException("Unknown incident " + threatId + " in tenant " + tenantId));
    }

    public List<MessageRecord> messages(String tenantId) {
        tenants.get(tenantId);
        return state(tenantId).messages(tenantId);
    }

    public MessageRecord message(String tenantId, String messageId) {
        tenants.get(tenantId);
        return state(tenantId).message(tenantId, messageId);
    }

    /** Overall threat status of a message: ACTIVE while any threat is bad. */
    public static ThreatStatus overall(MessageRecord m) {
        if (m.threats().values().stream().anyMatch(ThreatStatus::isBad)) return ThreatStatus.ACTIVE;
        return m.threats().values().stream().allMatch(s -> s == ThreatStatus.FALSE_POSITIVE)
                ? ThreatStatus.FALSE_POSITIVE : ThreatStatus.CLEARED;
    }

    private ThreatStateStore state(String tenantId) { return shards.forTenant(tenantId).state(); }

    private void audit(String tenantId, String mailbox, String action, String ref) {
        shards.forTenant(tenantId).audit()
                .append(new AuditEntry(clock.instant(), "threat", tenantId, mailbox, action, ref, ACTOR));
    }
}
