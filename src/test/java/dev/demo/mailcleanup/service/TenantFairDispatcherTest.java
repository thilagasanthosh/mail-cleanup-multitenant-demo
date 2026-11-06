package dev.demo.mailcleanup.service;

import dev.demo.mailcleanup.config.DemoProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFairDispatcherTest {

    @Test
    void smallTenantIsNotStarvedByNoisyTenant() throws Exception {
        TenantFairDispatcher dispatcher = new TenantFairDispatcher(new DemoProperties(2, 100, 0, List.of()));
        List<String> completionOrder = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(33);

        for (int i = 0; i < 30; i++) {          // noisy tenant submits first
            int n = i;
            dispatcher.submit("big", 2, () -> work(completionOrder, "big-" + n, done));
        }
        for (int i = 0; i < 3; i++) {
            int n = i;
            dispatcher.submit("small", 2, () -> work(completionOrder, "small-" + n, done));
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        int lastSmall = completionOrder.indexOf("small-2");
        // With FIFO, small tenant would finish at position ~33. Round-robin gets it done early.
        assertThat(lastSmall).isLessThan(12);
        assertThat(dispatcher.stats().get("big").get("peakRunning")).isLessThanOrEqualTo(2);
        dispatcher.shutdown();
    }

    @Test
    void throttledLaneIsPausedWithoutBlockingOtherLanes() throws Exception {
        TenantFairDispatcher dispatcher = new TenantFairDispatcher(new DemoProperties(2, 100, 0, List.of()));
        CountDownLatch otherLaneDone = new CountDownLatch(5);
        CountDownLatch retried = new CountDownLatch(1);
        long start = System.nanoTime();

        dispatcher.submit("big|M365", 2, () ->
                dispatcher.retryLater("big|M365", 2, java.time.Duration.ofMillis(500), retried::countDown));
        for (int i = 0; i < 5; i++) dispatcher.submit("small|FAKE", 2, otherLaneDone::countDown);

        assertThat(otherLaneDone.await(300, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(retried.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(500);
        assertThat(dispatcher.stats().get("big|M365").get("backoffs")).isEqualTo(1);
        dispatcher.shutdown();
    }

    @Test
    void repeated429sEscalateThePauseLikeACircuitBreaker() {
        TenantFairDispatcher dispatcher = new TenantFairDispatcher(new DemoProperties(2, 100, 0, List.of()));
        java.time.Duration first = dispatcher.retryLater("l", 1, java.time.Duration.ofMillis(10), () -> { });
        java.time.Duration second = dispatcher.retryLater("l", 1, java.time.Duration.ofMillis(10), () -> { });
        dispatcher.recordSuccess("l");
        java.time.Duration afterSuccess = dispatcher.retryLater("l", 1, java.time.Duration.ofMillis(10), () -> { });
        assertThat(first.toMillis()).isEqualTo(10);
        assertThat(second.toMillis()).isEqualTo(20);
        assertThat(afterSuccess.toMillis()).isEqualTo(10);
        dispatcher.shutdown();
    }

    private static void work(List<String> order, String name, CountDownLatch done) {
        try {
            Thread.sleep(20);
        } catch (InterruptedException ignored) { }
        order.add(name);
        done.countDown();
    }
}
