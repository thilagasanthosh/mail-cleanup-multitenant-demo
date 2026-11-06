package dev.demo.mailcleanup.service;

import dev.demo.mailcleanup.config.DemoProperties;
import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.metrics.TenantMetrics;
import dev.demo.mailcleanup.provider.MailboxProvider;
import dev.demo.mailcleanup.provider.ProviderRegistry;
import dev.demo.mailcleanup.provider.ThrottledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Runs one mailbox action through its action lane (customer x provider) with the failure rules:
 *  - 429: back off on that lane only, re-queue, try again (never block other lanes).
 *  - other errors: retry with exponential back-off.
 *  - retries exhausted: dead-letter queue (alert + replay). The action is reported done only after
 *    the provider confirmed it ("acknowledge after the action").
 */
@Component
public class ActionRunner {

    private static final Logger log = LoggerFactory.getLogger(ActionRunner.class);
    private static final Duration ERROR_BACKOFF = Duration.ofMillis(200);

    /** The provider work for one mailbox. Throw to signal failure. */
    @FunctionalInterface
    public interface MailboxWork {
        void run(MailboxProvider provider, TenantConfig tenant, String mailbox) throws Exception;
    }

    /** Called once with the final outcome. */
    public interface Outcome {
        void succeeded(int attempts);
        void failed(int attempts, String reason);
    }

    /**
     * @param action    label for logs, audit and the dead-letter queue, e.g. QUARANTINE, RESTORE
     * @param reference what the action is about (job id, message id)
     */
    public record ActionTask(TenantConfig tenant, String mailbox, String action, String reference,
                             MailboxWork work, Outcome outcome) { }

    private final TenantFairDispatcher dispatcher;
    private final ProviderRegistry providers;
    private final DeadLetterQueue deadLetters;
    private final TenantMetrics metrics;
    private final Clock clock;
    private final int maxAttempts;

    public ActionRunner(TenantFairDispatcher dispatcher, ProviderRegistry providers, DeadLetterQueue deadLetters,
                        TenantMetrics metrics, Clock clock, DemoProperties props) {
        this.dispatcher = dispatcher;
        this.providers = providers;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
        this.clock = clock;
        this.maxAttempts = props.actionMaxAttempts();
    }

    /** Lane = customer x provider: a throttled Google lane never slows the same customer's M365 lane. */
    public static String lane(TenantConfig tenant) { return tenant.id() + "|" + tenant.provider(); }

    public void submit(ActionTask task) {
        dispatcher.submit(lane(task.tenant()), task.tenant().maxConcurrency(), () -> attempt(task, 1));
    }

    private void attempt(ActionTask task, int attempt) {
        TenantConfig tenant = task.tenant();
        String lane = lane(tenant);
        metrics.providerCall(tenant.id());
        try {
            task.work().run(providers.forType(tenant.provider()), tenant, task.mailbox());
            dispatcher.recordSuccess(lane);
            task.outcome().succeeded(attempt);
        } catch (ThrottledException e) {
            metrics.throttled(tenant.id());
            if (attempt >= maxAttempts) {
                deadLetter(task, attempt, "still throttled after " + attempt + " attempts");
                return;
            }
            Duration pause = dispatcher.retryLater(lane, tenant.maxConcurrency(), e.retryAfter(),
                    () -> attempt(task, attempt + 1));
            log.warn("429   lane={} mailbox={} action={} -> lane paused {} ms, task re-queued",
                    lane, task.mailbox(), task.action(), pause.toMillis());
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (attempt >= maxAttempts) {
                deadLetter(task, attempt, reason);
                return;
            }
            Duration pause = ERROR_BACKOFF.multipliedBy(1L << (attempt - 1));
            log.warn("RETRY lane={} mailbox={} action={} attempt={} in {} ms: {}",
                    lane, task.mailbox(), task.action(), attempt, pause.toMillis(), reason);
            // Not the provider's fault in general: retry this task later, but do not pause the lane.
            dispatcher.submitAfter(lane, tenant.maxConcurrency(), pause, () -> attempt(task, attempt + 1));
        }
    }

    private void deadLetter(ActionTask task, int attempts, String reason) {
        metrics.deadLettered(task.tenant().id());
        deadLetters.add(task.tenant().id(), task.mailbox(), task.action(), task.reference(), attempts, reason,
                clock.instant(), () -> submit(task));
        task.outcome().failed(attempts, reason);
    }
}
