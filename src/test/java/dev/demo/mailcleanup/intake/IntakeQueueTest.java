package dev.demo.mailcleanup.intake;

import dev.demo.mailcleanup.model.ThreatAlert;
import dev.demo.mailcleanup.model.ThreatStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeQueueTest {

    private final IntakeQueue queue = new IntakeQueue(Clock.systemUTC());

    private static ThreatAlert alert(String tenant, String threat) {
        return new ThreatAlert(tenant, threat, ThreatStatus.ACTIVE, List.of());
    }

    @Test
    void smallCustomerIsNotStuckBehindAFlood() throws Exception {
        for (int i = 0; i < 1000; i++) queue.offer(alert("big", "b" + i));
        queue.offer(alert("small", "s0"));

        List<String> firstTwo = new ArrayList<>();
        for (int i = 0; i < 2; i++) firstTwo.add(queue.poll(Duration.ZERO).orElseThrow().alert().tenantId());
        assertThat(firstTwo).contains("small");
    }

    @Test
    void nackedAlertIsRedeliveredFirst() throws Exception {
        queue.offer(alert("t", "a"));
        queue.offer(alert("t", "b"));
        IntakeQueue.Delivery d = queue.poll(Duration.ZERO).orElseThrow();
        queue.nack(d);
        IntakeQueue.Delivery again = queue.poll(Duration.ZERO).orElseThrow();
        assertThat(again.alert().threatId()).isEqualTo("a");
        assertThat(again.deliveries()).isEqualTo(2);
    }

    @Test
    void ackRemovesFromInFlight() throws Exception {
        queue.offer(alert("t", "a"));
        IntakeQueue.Delivery d = queue.poll(Duration.ZERO).orElseThrow();
        assertThat(queue.inFlight()).isEqualTo(1);
        queue.ack(d);
        assertThat(queue.inFlight()).isZero();
        assertThat(queue.poll(Duration.ZERO)).isEmpty();
    }
}
