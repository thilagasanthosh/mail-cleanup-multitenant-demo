package dev.demo.mailcleanup.web;

import dev.demo.mailcleanup.model.ThreatAlert;
import dev.demo.mailcleanup.storage.IncidentRecord;
import dev.demo.mailcleanup.storage.MessageRecord;
import dev.demo.mailcleanup.threat.ThreatResponseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alert intake (called by the detection stack) and the customer APIs: incidents and messages.
 * Everything under /api/tenants/{tenantId} is rate limited per customer.
 */
@RestController
@RequestMapping("/api")
public class ThreatResponseController {

    private final ThreatResponseService service;

    public ThreatResponseController(ThreatResponseService service) {
        this.service = service;
    }

    /** Threat alerts: one entry per threat with its status (ACTIVE, CLEARED, FALSE_POSITIVE). */
    @PostMapping("/alerts")
    public ResponseEntity<Map<String, Object>> alerts(@RequestBody List<ThreatAlert> alerts) {
        int accepted = service.submit(alerts);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", accepted));
    }

    @GetMapping("/tenants/{tenantId}/incidents")
    public List<IncidentRecord> incidents(@PathVariable String tenantId) {
        return service.incidents(tenantId);
    }

    /** One incident with the state of every message (and mailbox copy) it touched. */
    @GetMapping("/tenants/{tenantId}/incidents/{threatId}")
    public Map<String, Object> incident(@PathVariable String tenantId, @PathVariable String threatId) {
        IncidentRecord incident = service.incident(tenantId, threatId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("incident", incident);
        out.put("messages", incident.messageIds().stream().map(id -> view(service.message(tenantId, id))).toList());
        return out;
    }

    @PostMapping("/tenants/{tenantId}/incidents/{threatId}/approve")
    public IncidentRecord approve(@PathVariable String tenantId, @PathVariable String threatId) {
        return service.approve(tenantId, threatId);
    }

    @GetMapping("/tenants/{tenantId}/messages")
    public List<Map<String, Object>> messages(@PathVariable String tenantId) {
        return service.messages(tenantId).stream().map(ThreatResponseController::view).toList();
    }

    @GetMapping("/tenants/{tenantId}/messages/{messageId}")
    public Map<String, Object> message(@PathVariable String tenantId, @PathVariable String messageId) {
        return view(service.message(tenantId, messageId));
    }

    private static Map<String, Object> view(MessageRecord m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messageId", m.messageId());
        out.put("sender", m.sender());
        out.put("subject", m.subject());
        out.put("status", ThreatResponseService.overall(m));
        out.put("threats", m.threats());
        out.put("mailboxes", m.mailboxes());
        return out;
    }
}
