package dev.demo.mailcleanup.model;

import java.util.List;

/** Outcome for one (tenant, mailbox) task. quarantinedIds are kept so the action can be undone. */
public record MailboxResult(String tenantId, String mailbox, MailboxStatus status, int messages,
                            int attempts, String detail, long finishedAfterMs, List<String> quarantinedIds) { }
