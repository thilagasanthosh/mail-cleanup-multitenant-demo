package dev.demo.mailcleanup.threat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.demo.mailcleanup.model.Decision.NONE;
import static dev.demo.mailcleanup.model.Decision.QUARANTINE;
import static dev.demo.mailcleanup.model.Decision.RESTORE;
import static dev.demo.mailcleanup.model.ThreatStatus.ACTIVE;
import static dev.demo.mailcleanup.model.ThreatStatus.CLEARED;
import static dev.demo.mailcleanup.model.ThreatStatus.FALSE_POSITIVE;
import static org.assertj.core.api.Assertions.assertThat;

class RemediationDeciderTest {

    @Test
    void quarantinesWhenAnyThreatIsActive() {
        assertThat(RemediationDecider.decide(List.of(CLEARED, ACTIVE), false)).isEqualTo(QUARANTINE);
    }

    @Test
    void keepsQuarantinedWhileOneThreatIsStillActive() {
        assertThat(RemediationDecider.decide(List.of(CLEARED, ACTIVE), true)).isEqualTo(NONE);
    }

    @Test
    void restoresOnlyWhenEveryThreatIsCleared() {
        assertThat(RemediationDecider.decide(List.of(CLEARED, FALSE_POSITIVE), true)).isEqualTo(RESTORE);
    }

    @Test
    void doesNothingForClearedMessageThatWasNeverQuarantined() {
        assertThat(RemediationDecider.decide(List.of(CLEARED), false)).isEqualTo(NONE);
    }

    @Test
    void doesNothingWithoutThreats() {
        assertThat(RemediationDecider.decide(List.of(), true)).isEqualTo(NONE);
    }
}
