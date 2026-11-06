package dev.demo.mailcleanup.storage;

import dev.demo.mailcleanup.model.ThreatStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** One threat as the customer sees it: status history and the messages it was found in. */
public record IncidentRecord(String tenantId, String threatId, ThreatStatus status, Instant firstSeen,
                             Instant lastUpdated, Set<String> messageIds, List<StatusChange> history,
                             boolean awaitingApproval, boolean approved) {

    public record StatusChange(Instant at, ThreatStatus status) { }
}
