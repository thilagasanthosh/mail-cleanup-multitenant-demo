package dev.demo.mailcleanup.threat;

import dev.demo.mailcleanup.model.Decision;
import dev.demo.mailcleanup.model.ThreatStatus;

import java.util.Collection;

/**
 * Per-message decision from all of its threats:
 *  - any threat still bad (ACTIVE)          -> quarantine (if not already)
 *  - every threat cleared / false positive  -> restore (only if it was quarantined)
 * A message with a malicious URL AND a malicious attachment is restored only when BOTH are cleared.
 */
public final class RemediationDecider {

    private RemediationDecider() { }

    public static Decision decide(Collection<ThreatStatus> threats, boolean currentlyQuarantined) {
        if (threats.isEmpty()) return Decision.NONE;
        boolean anyBad = threats.stream().anyMatch(ThreatStatus::isBad);
        if (anyBad) return currentlyQuarantined ? Decision.NONE : Decision.QUARANTINE;
        return currentlyQuarantined ? Decision.RESTORE : Decision.NONE;
    }
}
