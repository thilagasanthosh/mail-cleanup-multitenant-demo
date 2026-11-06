package dev.demo.mailcleanup.intake;

import dev.demo.mailcleanup.model.AlertMessage;
import dev.demo.mailcleanup.model.ThreatAlert;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Normalise + dedup. The detection stack re-sends the same verdict many times (every poll, every
 * re-scan); acting on each copy would multiply provider calls on the worst day.
 *  - Normalise: trim ids, lower-case addresses, merge duplicate messages in one alert.
 *  - Dedup on (tenant, threat id): an alert identical to the last one processed (same status, same
 *    messages) is dropped; a status change, or the threat showing up in new messages, is new work.
 *    Production: this "last seen" row lives in the state store, so dedup survives restarts.
 */
@Component
public class AlertNormalizer {

    private final Map<String, String> lastProcessed = new ConcurrentHashMap<>();

    public Optional<ThreatAlert> normaliseAndDedup(ThreatAlert raw) {
        ThreatAlert alert = normalise(raw);
        String threatKey = alert.tenantId() + "|" + alert.threatId();
        String fingerprint = fingerprint(alert);
        String previous = lastProcessed.put(threatKey, fingerprint);
        return fingerprint.equals(previous) ? Optional.empty() : Optional.of(alert);
    }

    static ThreatAlert normalise(ThreatAlert a) {
        if (a.tenantId() == null || a.tenantId().isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (a.threatId() == null || a.threatId().isBlank()) throw new IllegalArgumentException("threatId is required");
        if (a.status() == null) throw new IllegalArgumentException("status is required");
        Map<String, AlertMessage> byId = new LinkedHashMap<>();
        for (AlertMessage m : a.messages()) {
            if (m.messageId() == null || m.messageId().isBlank()) throw new IllegalArgumentException("messageId is required");
            String id = m.messageId().trim();
            List<String> recipients = new ArrayList<>(m.recipients().stream().map(AlertNormalizer::address).distinct().toList());
            AlertMessage existing = byId.get(id);
            if (existing != null) {
                existing.recipients().stream().filter(r -> !recipients.contains(r)).forEach(recipients::add);
            }
            byId.put(id, new AlertMessage(id, address(m.sender()), m.subject() == null ? null : m.subject().trim(), recipients));
        }
        return new ThreatAlert(a.tenantId().trim(), a.threatId().trim(), a.status(), List.copyOf(byId.values()));
    }

    private static String fingerprint(ThreatAlert a) {
        TreeSet<String> ids = new TreeSet<>();
        a.messages().forEach(m -> ids.add(m.messageId()));
        return a.status() + "|" + String.join(",", ids);
    }

    private static String address(String s) { return s == null ? null : s.trim().toLowerCase(); }
}
