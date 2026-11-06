package dev.demo.mailcleanup.model;

import java.util.List;

/** A threat alert: one threat (URL, attachment, ...) with its current status and the messages that carry it. */
public record ThreatAlert(String tenantId, String threatId, ThreatStatus status, List<AlertMessage> messages) {
    public ThreatAlert {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
