package dev.demo.mailcleanup.intake;

import dev.demo.mailcleanup.model.ThreatAlert;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Intake queue in front of the pipeline, so a 10M-alert worst day is absorbed instead of overloading
 * the state store. One partition per customer and round-robin polling: one customer's flood never
 * delays another customer's alerts (production: Kafka partitioned by customer id, or SQS FIFO with
 * the customer id as message group id).
 * Delivery semantics match Kafka/SQS: {@link #poll} hands out a delivery, the consumer must
 * {@link #ack} it after processing, or {@link #nack} it to have it redelivered.
 */
@Component
public class IntakeQueue {

    public record Delivery(long offset, ThreatAlert alert, Instant enqueuedAt, int deliveries) { }

    private final Clock clock;
    private final Map<String, Deque<Delivery>> partitions = new LinkedHashMap<>();
    private final Map<Long, Delivery> inFlight = new LinkedHashMap<>();
    private final List<String> rotation = new ArrayList<>();
    private int cursor = 0;
    private long nextOffset = 0;

    public IntakeQueue(Clock clock) {
        this.clock = clock;
    }

    public synchronized void offer(ThreatAlert alert) {
        partition(alert.tenantId()).addLast(new Delivery(nextOffset++, alert, clock.instant(), 0));
        notifyAll();
    }

    /** Next delivery, fair across customers. Waits up to {@code timeout}. */
    public synchronized Optional<Delivery> poll(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (int i = 0; i < rotation.size(); i++) {
                String tenant = rotation.get(cursor);
                cursor = (cursor + 1) % rotation.size();
                Delivery d = partitions.get(tenant).pollFirst();
                if (d != null) {
                    Delivery out = new Delivery(d.offset(), d.alert(), d.enqueuedAt(), d.deliveries() + 1);
                    inFlight.put(out.offset(), out);
                    return Optional.of(out);
                }
            }
            long waitMs = (deadline - System.nanoTime()) / 1_000_000;
            if (waitMs <= 0) return Optional.empty();
            wait(waitMs);
        }
    }

    /** Processing finished: the alert is now safe in the state store and its actions are queued. */
    public synchronized void ack(Delivery d) { inFlight.remove(d.offset()); }

    /** Processing failed: put it back at the head of its partition for redelivery. */
    public synchronized void nack(Delivery d) {
        if (inFlight.remove(d.offset()) != null) {
            partition(d.alert().tenantId()).addFirst(d);
            notifyAll();
        }
    }

    public synchronized int depth(String tenantId) {
        Deque<Delivery> p = partitions.get(tenantId);
        return p == null ? 0 : p.size();
    }

    /** Age of the oldest waiting alert for this customer: the main "are we keeping up?" signal. */
    public synchronized long oldestAgeMs(String tenantId) {
        Deque<Delivery> p = partitions.get(tenantId);
        if (p == null || p.isEmpty()) return 0;
        return Duration.between(p.peekFirst().enqueuedAt(), clock.instant()).toMillis();
    }

    public synchronized int inFlight() { return inFlight.size(); }

    private Deque<Delivery> partition(String tenantId) {
        return partitions.computeIfAbsent(tenantId, t -> {
            rotation.add(t);
            return new ArrayDeque<>();
        });
    }
}
