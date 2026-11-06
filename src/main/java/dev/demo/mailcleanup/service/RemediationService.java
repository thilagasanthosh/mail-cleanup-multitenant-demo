package dev.demo.mailcleanup.service;

import dev.demo.mailcleanup.config.DemoProperties;
import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.config.TenantRegistry;
import dev.demo.mailcleanup.model.AuditEntry;
import dev.demo.mailcleanup.model.FoundMessage;
import dev.demo.mailcleanup.model.MailboxResult;
import dev.demo.mailcleanup.model.MailboxStatus;
import dev.demo.mailcleanup.model.SearchCriteria;
import dev.demo.mailcleanup.service.ActionRunner.ActionTask;
import dev.demo.mailcleanup.service.ActionRunner.Outcome;
import dev.demo.mailcleanup.storage.ShardRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyst-driven "pull this campaign" jobs (search by sender / subject). Fans a job out into one
 * action per (tenant, mailbox) and runs them through the {@link ActionRunner}: fair lanes, 429 back-off,
 * retries and the dead-letter queue. A failure in one tenant's mailbox never stops other tenants.
 * The alert-driven path (threat verdicts) is {@code ThreatResponseService}.
 */
@Service
public class RemediationService {

    private static final Logger log = LoggerFactory.getLogger(RemediationService.class);

    private final TenantRegistry tenants;
    private final ActionRunner runner;
    private final ShardRouter shards;
    private final DemoProperties props;
    private final Clock clock;
    private final Map<String, RemediationJob> jobs = new ConcurrentHashMap<>();

    public RemediationService(TenantRegistry tenants, ActionRunner runner, ShardRouter shards,
                              DemoProperties props, Clock clock) {
        this.tenants = tenants;
        this.runner = runner;
        this.shards = shards;
        this.props = props;
        this.clock = clock;
    }

    public RemediationJob start(SearchCriteria criteria, List<String> tenantIds, boolean approved) {
        // Guardrail 1: never run with empty criteria (it would match every inbox message).
        if (criteria == null || criteria.isEmpty()) {
            throw new IllegalArgumentException("sender or subjectContains is required");
        }
        List<TenantConfig> targets = (tenantIds == null || tenantIds.isEmpty())
                ? List.copyOf(tenants.all())
                : tenantIds.stream().map(tenants::get).toList();

        // Guardrail 2: wide-impact actions need explicit approval (limits damage from a false positive).
        int mailboxes = targets.stream().mapToInt(t -> t.mailboxes().size()).sum();
        if (mailboxes > props.approvalThreshold() && !approved) {
            throw new ApprovalRequiredException(mailboxes, props.approvalThreshold());
        }

        RemediationJob job = new RemediationJob(UUID.randomUUID().toString().substring(0, 8), criteria);
        jobs.put(job.id(), job);
        log.info("JOB {} started: criteria={} tenants={} mailboxes={}", job.id(), criteria,
                targets.stream().map(TenantConfig::id).toList(), mailboxes);

        for (TenantConfig tenant : targets) {
            for (String mailbox : tenant.mailboxes()) {
                job.put(new MailboxResult(tenant.id(), mailbox, MailboxStatus.PENDING, 0, 0, null, -1, List.of()));
                runner.submit(quarantineTask(job, tenant, mailbox));
            }
        }
        return job;
    }

    private ActionTask quarantineTask(RemediationJob job, TenantConfig tenant, String mailbox) {
        List<String> quarantined = new ArrayList<>();
        return new ActionTask(tenant, mailbox, "QUARANTINE", job.id(),
                (provider, t, m) -> {
                    // Idempotent: a retry or replay finds only what is still in the inbox.
                    for (FoundMessage found : provider.find(t, m, job.criteria())) {
                        String newId = provider.quarantine(t, m, found.id());
                        quarantined.add(newId);
                        audit(job.id(), t.id(), m, "QUARANTINE", newId, "auto-remediation");
                    }
                },
                new Outcome() {
                    @Override public void succeeded(int attempts) {
                        MailboxStatus status = quarantined.isEmpty() ? MailboxStatus.NOT_FOUND : MailboxStatus.QUARANTINED;
                        job.put(new MailboxResult(tenant.id(), mailbox, status, quarantined.size(), attempts, null,
                                job.elapsedMs(), List.copyOf(quarantined)));
                        log.info("DONE  job={} tenant={} mailbox={} status={} messages={} attempts={}",
                                job.id(), tenant.id(), mailbox, status, quarantined.size(), attempts);
                    }
                    @Override public void failed(int attempts, String reason) {
                        fail(job, tenant, mailbox, attempts, reason, List.copyOf(quarantined));
                    }
                });
    }

    /** Undo the job: move every quarantined message back to its inbox (false-positive recovery). */
    public RemediationJob restore(String jobId) {
        RemediationJob job = job(jobId);
        for (String tenantId : job.tenantIds()) {
            TenantConfig tenant = tenants.get(tenantId);
            for (MailboxResult r : job.forTenant(tenantId).values()) {
                if (r.status() != MailboxStatus.QUARANTINED) continue;
                runner.submit(new ActionTask(tenant, r.mailbox(), "RESTORE", job.id(),
                        (provider, t, m) -> {
                            for (String id : r.quarantinedIds()) {
                                provider.restore(t, m, id);
                                audit(job.id(), tenantId, m, "RESTORE", id, "analyst");
                            }
                        },
                        new Outcome() {
                            @Override public void succeeded(int attempts) {
                                job.put(new MailboxResult(tenantId, r.mailbox(), MailboxStatus.RESTORED, r.messages(),
                                        r.attempts(), null, job.elapsedMs(), List.of()));
                            }
                            @Override public void failed(int attempts, String reason) {
                                fail(job, tenant, r.mailbox(), attempts, "restore failed: " + reason, r.quarantinedIds());
                            }
                        }));
            }
        }
        return job;
    }

    private void audit(String jobId, String tenantId, String mailbox, String action, String messageId, String actor) {
        shards.forTenant(tenantId).audit()
                .append(new AuditEntry(clock.instant(), jobId, tenantId, mailbox, action, messageId, actor));
    }

    private void fail(RemediationJob job, TenantConfig tenant, String mailbox, int attempts, String reason,
                      List<String> quarantinedSoFar) {
        job.put(new MailboxResult(tenant.id(), mailbox, MailboxStatus.FAILED, quarantinedSoFar.size(), attempts,
                reason, job.elapsedMs(), quarantinedSoFar));
        log.error("FAIL  job={} tenant={} mailbox={} reason={}", job.id(), tenant.id(), mailbox, reason);
    }

    public RemediationJob job(String jobId) {
        RemediationJob job = jobs.get(jobId);
        if (job == null) throw new NoSuchElementException("Unknown job: " + jobId);
        return job;
    }

    /** Audit entries for ONE tenant only, read from that tenant's shard: tenants never see each other's data. */
    public List<AuditEntry> auditFor(String tenantId) {
        tenants.get(tenantId);
        return shards.forTenant(tenantId).audit().forTenant(tenantId);
    }

    public static class ApprovalRequiredException extends RuntimeException {
        public ApprovalRequiredException(int mailboxes, int threshold) {
            super("Job touches " + mailboxes + " mailboxes (threshold " + threshold + "). Resend with \"approved\": true.");
        }
    }
}
