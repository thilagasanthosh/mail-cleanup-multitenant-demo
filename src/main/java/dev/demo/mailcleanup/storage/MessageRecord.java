package dev.demo.mailcleanup.storage;

import dev.demo.mailcleanup.model.ThreatStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One delivered message and every threat linked to it. Action is per message, alerts are per threat.
 *
 * @param mailboxes mailbox -> its state (provider ids are kept so a quarantine can be undone)
 */
public record MessageRecord(String tenantId, String messageId, String sender, String subject,
                            List<String> recipients, Map<String, ThreatStatus> threats,
                            Map<String, MailboxState> mailboxes, Instant firstSeen) {

    /** NOT_FOUND: the copy was already gone (user deleted it); nothing to quarantine or restore. */
    public enum MailboxStatus { QUARANTINED, RESTORED, NOT_FOUND }

    public record MailboxState(MailboxStatus status, List<String> providerIds, Instant at) { }

    public boolean anyQuarantined() {
        return mailboxes.values().stream().anyMatch(m -> m.status() == MailboxStatus.QUARANTINED);
    }
}
