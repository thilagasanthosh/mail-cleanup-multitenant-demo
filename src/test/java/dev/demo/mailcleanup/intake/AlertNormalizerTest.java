package dev.demo.mailcleanup.intake;

import dev.demo.mailcleanup.model.AlertMessage;
import dev.demo.mailcleanup.model.ThreatAlert;
import dev.demo.mailcleanup.model.ThreatStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertNormalizerTest {

    private final AlertNormalizer normalizer = new AlertNormalizer();

    private static ThreatAlert alert(ThreatStatus status, String messageId) {
        return new ThreatAlert(" clinic ", "url-1", status,
                List.of(new AlertMessage(messageId, " Bad@Evil.example ", " Hi ", List.of("Alice@Clinic.example"))));
    }

    @Test
    void normalisesIdsAndAddresses() {
        ThreatAlert a = normalizer.normaliseAndDedup(alert(ThreatStatus.ACTIVE, " <m1> ")).orElseThrow();
        assertThat(a.tenantId()).isEqualTo("clinic");
        assertThat(a.messages().get(0).messageId()).isEqualTo("<m1>");
        assertThat(a.messages().get(0).sender()).isEqualTo("bad@evil.example");
        assertThat(a.messages().get(0).recipients()).containsExactly("alice@clinic.example");
    }

    @Test
    void dropsRepeatedVerdict() {
        assertThat(normalizer.normaliseAndDedup(alert(ThreatStatus.ACTIVE, "m1"))).isPresent();
        assertThat(normalizer.normaliseAndDedup(alert(ThreatStatus.ACTIVE, " m1"))).isEmpty();
    }

    @Test
    void statusChangeIsNewWork() {
        normalizer.normaliseAndDedup(alert(ThreatStatus.ACTIVE, "m1"));
        assertThat(normalizer.normaliseAndDedup(alert(ThreatStatus.CLEARED, "m1"))).isPresent();
    }

    @Test
    void threatTurningBadAgainAfterClearIsNewWork() {
        normalizer.normaliseAndDedup(alert(ThreatStatus.ACTIVE, "m1"));
        normalizer.normaliseAndDedup(alert(ThreatStatus.CLEARED, "m1"));
        assertThat(normalizer.normaliseAndDedup(alert(ThreatStatus.ACTIVE, "m1"))).isPresent();
    }

    @Test
    void mergesDuplicateMessagesInOneAlert() {
        ThreatAlert raw = new ThreatAlert("clinic", "t", ThreatStatus.ACTIVE, List.of(
                new AlertMessage("m1", "x@y", "s", List.of("a@clinic.example")),
                new AlertMessage("m1", "x@y", "s", List.of("b@clinic.example"))));
        ThreatAlert a = normalizer.normaliseAndDedup(raw).orElseThrow();
        assertThat(a.messages()).hasSize(1);
        assertThat(a.messages().get(0).recipients()).containsExactlyInAnyOrder("a@clinic.example", "b@clinic.example");
    }

    @Test
    void rejectsAlertWithoutThreatId() {
        assertThatThrownBy(() -> normalizer.normaliseAndDedup(new ThreatAlert("c", " ", ThreatStatus.ACTIVE, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
