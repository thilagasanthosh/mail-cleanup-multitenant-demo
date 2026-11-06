package dev.demo.mailcleanup.model;

import java.util.List;

/**
 * One delivered message that a threat alert refers to.
 *
 * @param messageId  stable id across mailboxes (production: Internet-Message-ID)
 * @param recipients addresses on the envelope; may include distribution lists
 */
public record AlertMessage(String messageId, String sender, String subject, List<String> recipients) {
    public AlertMessage {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }
}
