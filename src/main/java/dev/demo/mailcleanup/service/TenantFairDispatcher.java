package dev.demo.mailcleanup.service;

import dev.demo.mailcleanup.config.DemoProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fair scheduling across action lanes (one lane per customer x provider).
 *  - One queue per lane (a big tenant's backlog waits in ITS queue, not in front of everyone).
 *  - Round-robin: each free slot goes to the next lane that has work.
 *  - Per-lane cap (protects that tenant's provider quota, e.g. Graph throttling) and a global cap.
 *  - 429 back-off: {@link #retryLater} pauses ONLY that lane and re-queues the task at its head.
 *    The worker slot is released immediately, so other lanes never wait behind a throttled one.
 *  - Work runs on virtual threads: mailbox calls are blocking HTTP I/O.
 * In production the durable source of work is Kafka/SQS; this in-memory queue is the local scheduler.
 */
@Component
public class TenantFairDispatcher {

    private static final Duration MAX_PAUSE = Duration.ofSeconds(30);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lane-backoff-timer");
        t.setDaemon(true);
        return t;
    });
    private final int globalMax;

    private final Map<String, Deque<Runnable>> queues = new LinkedHashMap<>();
    private final Map<String, Integer> limits = new HashMap<>();
    private final Map<String, Integer> running = new HashMap<>();
    private final Map<String, Integer> peakRunning = new HashMap<>();
    private final Map<String, Long> pausedUntilNanos = new HashMap<>();
    private final Map<String, Integer> backoffs = new HashMap<>();
    private final Map<String, Integer> consecutiveBackoffs = new HashMap<>();
    private final List<String> rotation = new ArrayList<>();
    private int cursor = 0;
    private int globalRunning = 0;

    public TenantFairDispatcher(DemoProperties props) {
        this.globalMax = props.globalMaxConcurrency();
    }

    public synchronized void submit(String lane, int laneLimit, Runnable task) {
        lane(lane, laneLimit).addLast(task);
        drain();
    }

    /**
     * Provider said 429: pause this lane, put the task back at the head of its queue.
     * Acts as a lane-level circuit breaker: repeated 429s without a success in between double the pause
     * (capped at {@link #MAX_PAUSE}), and the provider's Retry-After is always respected.
     * @return the pause actually applied
     */
    public synchronized Duration retryLater(String lane, int laneLimit, Duration retryAfter, Runnable task) {
        lane(lane, laneLimit).addFirst(task);
        int streak = consecutiveBackoffs.merge(lane, 1, Integer::sum);
        long escalated = retryAfter.toMillis() << Math.min(streak - 1, 5);
        Duration delay = Duration.ofMillis(Math.min(Math.max(retryAfter.toMillis(), escalated), MAX_PAUSE.toMillis()));
        pausedUntilNanos.merge(lane, System.nanoTime() + delay.toNanos(), Math::max);
        backoffs.merge(lane, 1, Integer::sum);
        timer.schedule(this::wake, delay.toMillis() + 1, TimeUnit.MILLISECONDS);
        return delay;
    }

    /** Re-queue a task after a delay without pausing its lane (used for non-throttling errors). */
    public void submitAfter(String lane, int laneLimit, Duration delay, Runnable task) {
        timer.schedule(() -> submit(lane, laneLimit, task), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** A call on this lane succeeded: close the breaker (reset the back-off streak). */
    public synchronized void recordSuccess(String lane) { consecutiveBackoffs.remove(lane); }

    private synchronized void wake() { drain(); }

    private Deque<Runnable> lane(String lane, int laneLimit) {
        if (!queues.containsKey(lane)) {
            queues.put(lane, new ArrayDeque<>());
            rotation.add(lane);
        }
        limits.put(lane, laneLimit);
        return queues.get(lane);
    }

    /** Hand free slots to lanes in round-robin order, respecting both caps and any back-off pause. */
    private void drain() {
        boolean progress = true;
        long now = System.nanoTime();
        while (progress && globalRunning < globalMax) {
            progress = false;
            for (int i = 0; i < rotation.size() && globalRunning < globalMax; i++) {
                String lane = rotation.get(cursor);
                cursor = (cursor + 1) % rotation.size();
                Deque<Runnable> q = queues.get(lane);
                int busy = running.getOrDefault(lane, 0);
                boolean paused = pausedUntilNanos.getOrDefault(lane, 0L) - now > 0;
                if (!q.isEmpty() && busy < limits.get(lane) && !paused) {
                    Runnable task = q.pollFirst();
                    running.put(lane, busy + 1);
                    peakRunning.merge(lane, busy + 1, Math::max);
                    globalRunning++;
                    executor.submit(() -> runAndRelease(lane, task));
                    progress = true;
                }
            }
        }
    }

    private void runAndRelease(String lane, Runnable task) {
        try {
            task.run();
        } finally {
            synchronized (this) {
                running.merge(lane, -1, Integer::sum);
                globalRunning--;
                drain();
            }
        }
    }

    /** Snapshot for the stats endpoint: queued, running, peak concurrency and back-offs per lane. */
    public synchronized Map<String, Map<String, Integer>> stats() {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (String lane : rotation) {
            out.put(lane, Map.of(
                    "queued", queues.get(lane).size(),
                    "running", running.getOrDefault(lane, 0),
                    "peakRunning", peakRunning.getOrDefault(lane, 0),
                    "limit", limits.get(lane),
                    "backoffs", backoffs.getOrDefault(lane, 0)));
        }
        return out;
    }

    /** Queued tasks across every lane that belongs to this tenant (lane ids start with "tenant|"). */
    public synchronized int queuedFor(String tenantId) {
        return queues.entrySet().stream()
                .filter(e -> e.getKey().equals(tenantId) || e.getKey().startsWith(tenantId + "|"))
                .mapToInt(e -> e.getValue().size()).sum();
    }

    @PreDestroy
    public void shutdown() {
        timer.shutdownNow();
        executor.shutdownNow();
    }
}
