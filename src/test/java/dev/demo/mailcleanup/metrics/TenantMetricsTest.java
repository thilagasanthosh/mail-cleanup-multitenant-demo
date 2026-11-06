package dev.demo.mailcleanup.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class TenantMetricsTest {

    @Test
    void p95IsTheValueBelowWhich95PercentFall() {
        assertThat(TenantMetrics.p95(LongStream.rangeClosed(1, 100).boxed().toList())).isEqualTo(95);
        assertThat(TenantMetrics.p95(List.of())).isZero();
    }

    @Test
    void reports429RatePerCustomer() {
        TenantMetrics m = new TenantMetrics();
        for (int i = 0; i < 4; i++) m.providerCall("a");
        m.throttled("a");
        m.quarantined("a", Duration.ofMillis(120));
        assertThat(m.snapshot("a")).containsEntry("rate429", 0.25).containsEntry("p95TimeToQuarantineMs", 120L);
        assertThat(m.snapshot("b")).containsEntry("providerCalls", 0L);
    }
}
