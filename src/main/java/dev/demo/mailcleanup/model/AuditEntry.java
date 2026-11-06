package dev.demo.mailcleanup.model;

import java.time.Instant;

/** Who did what, where. Every automated action must be auditable. */
public record AuditEntry(Instant at, String jobId, String tenantId, String mailbox, String action,
                         String messageId, String actor) { }
