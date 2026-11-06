package dev.demo.mailcleanup.service;

import dev.demo.mailcleanup.config.DemoProperties;
import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.metrics.TenantMetrics;
import dev.demo.mailcleanup.model.FoundMessage;
import dev.demo.mailcleanup.model.ProviderType;
import dev.demo.mailcleanup.model.SearchCriteria;
import dev.demo.mailcleanup.provider.MailboxProvider;
import dev.demo.mailcleanup.provider.ProviderRegistry;
import dev.demo.mailcleanup.provider.ThrottledException;
import dev.demo.mailcleanup.service.ActionRunner.ActionTask;
import dev.demo.mailcleanup.service.ActionRunner.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRunnerTest {

    private final DemoProperties props = new DemoProperties(4, 100, 0, 2, List.of(), null, null);
    private final TenantFairDispatcher dispatcher = new TenantFairDispatcher(props);
    private final DeadLetterQueue dlq = new DeadLetterQueue();
    private final TenantMetrics metrics = new TenantMetrics();
    private final ActionRunner runner = new ActionRunner(dispatcher, new ProviderRegistry(List.of(new NoopProvider())),
            dlq, metrics, Clock.systemUTC(), props);
    private final TenantConfig tenant = new TenantConfig("acme", ProviderType.FAKE, 2, List.of("a@acme.example"));

    @AfterEach
    void stop() { dispatcher.shutdown(); }

    @Test
    void retriesThenDeadLettersThenReplaySucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> firstOutcome = new CompletableFuture<>();
        CompletableFuture<Integer> replayed = new CompletableFuture<>();
        ActionTask task = new ActionTask(tenant, "a@acme.example", "QUARANTINE", "msg-1",
                (p, t, m) -> { if (calls.incrementAndGet() <= 2) throw new IllegalStateException("provider down"); },
                new Outcome() {
                    @Override public void succeeded(int attempts) { replayed.complete(attempts); }
                    @Override public void failed(int attempts, String reason) { firstOutcome.complete(reason); }
                });

        runner.submit(task);
        assertThat(firstOutcome.get(5, TimeUnit.SECONDS)).isEqualTo("provider down");
        assertThat(dlq.forTenant("acme")).singleElement()
                .satisfies(d -> assertThat(d.attempts()).isEqualTo(2));
        assertThat(metrics.snapshot("acme")).containsEntry("deadLettered", 1L);

        dlq.replay("acme", dlq.forTenant("acme").get(0).id());
        assertThat(replayed.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(dlq.forTenant("acme")).isEmpty();
    }

    @Test
    void throttlingIsRetriedAndCounted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Integer> done = new CompletableFuture<>();
        runner.submit(new ActionTask(tenant, "a@acme.example", "QUARANTINE", "msg-1",
                (p, t, m) -> { if (calls.incrementAndGet() == 1) throw new ThrottledException(Duration.ofMillis(50)); },
                new Outcome() {
                    @Override public void succeeded(int attempts) { done.complete(attempts); }
                    @Override public void failed(int attempts, String reason) { done.completeExceptionally(new AssertionError(reason)); }
                }));
        assertThat(done.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(metrics.snapshot("acme")).containsEntry("throttled429", 1L).containsEntry("rate429", 0.5);
    }

    private static final class NoopProvider implements MailboxProvider {
        @Override public ProviderType type() { return ProviderType.FAKE; }
        @Override public List<FoundMessage> find(TenantConfig t, String m, SearchCriteria c) { return List.of(); }
        @Override public String quarantine(TenantConfig t, String m, String id) { return id; }
        @Override public void restore(TenantConfig t, String m, String id) { }
    }
}
