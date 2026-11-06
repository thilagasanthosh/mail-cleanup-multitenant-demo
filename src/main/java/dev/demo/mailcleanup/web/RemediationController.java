package dev.demo.mailcleanup.web;

import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.config.TenantRegistry;
import dev.demo.mailcleanup.model.AuditEntry;
import dev.demo.mailcleanup.model.FoundMessage;
import dev.demo.mailcleanup.model.MailboxResult;
import dev.demo.mailcleanup.model.MailboxStatus;
import dev.demo.mailcleanup.model.SearchCriteria;
import dev.demo.mailcleanup.provider.ProviderRegistry;
import dev.demo.mailcleanup.service.RemediationJob;
import dev.demo.mailcleanup.service.RemediationService;
import dev.demo.mailcleanup.service.TenantFairDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RemediationController {

    public record StartRequest(String sender, String subjectContains, List<String> tenantIds, boolean approved) { }

    private final RemediationService service;
    private final TenantRegistry tenants;
    private final ProviderRegistry providers;
    private final TenantFairDispatcher dispatcher;

    public RemediationController(RemediationService service, TenantRegistry tenants,
                                 ProviderRegistry providers, TenantFairDispatcher dispatcher) {
        this.service = service;
        this.tenants = tenants;
        this.providers = providers;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/tenants")
    public List<String> tenants() {
        return tenants.all().stream().map(TenantConfig::describe).toList();
    }

    /** Read-only look at one mailbox's inbox (before / after view). */
    @GetMapping("/tenants/{tenantId}/mailboxes/{mailbox}/inbox")
    public List<FoundMessage> inbox(@PathVariable String tenantId, @PathVariable String mailbox) {
        TenantConfig tenant = tenants.get(tenantId);
        if (!tenant.mailboxes().contains(mailbox)) {
            throw new java.util.NoSuchElementException("Mailbox " + mailbox + " is not in tenant " + tenantId);
        }
        return providers.forType(tenant.provider()).find(tenant, mailbox, new SearchCriteria(null, null));
    }

    /** "This campaign is malicious: pull it from every mailbox." */
    @PostMapping("/remediations")
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartRequest req) {
        RemediationJob job = service.start(new SearchCriteria(req.sender(), req.subjectContains()),
                req.tenantIds(), req.approved());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("jobId", job.id(), "status", "/api/remediations/" + job.id()));
    }

    /** Per-tenant progress: shows small tenants finishing while a big tenant is still running. */
    @GetMapping("/remediations/{jobId}")
    public Map<String, Object> summary(@PathVariable String jobId) {
        RemediationJob job = service.job(jobId);
        Map<String, Object> perTenant = new LinkedHashMap<>();
        for (String tenantId : job.tenantIds()) {
            var results = job.forTenant(tenantId).values();
            long pending = results.stream().filter(r -> r.status() == MailboxStatus.PENDING).count();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("mailboxes", results.size());
            s.put("pending", pending);
            s.put("quarantinedMailboxes", count(results, MailboxStatus.QUARANTINED));
            s.put("messagesQuarantined", results.stream().filter(r -> r.status() == MailboxStatus.QUARANTINED)
                    .mapToInt(MailboxResult::messages).sum());
            s.put("notFound", count(results, MailboxStatus.NOT_FOUND));
            s.put("failed", count(results, MailboxStatus.FAILED));
            s.put("restored", count(results, MailboxStatus.RESTORED));
            s.put("allDoneAfterMs", pending > 0 ? null
                    : results.stream().mapToLong(MailboxResult::finishedAfterMs).max().orElse(0));
            perTenant.put(tenantId, s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.id());
        out.put("criteria", job.criteria());
        out.put("elapsedMs", job.elapsedMs());
        out.put("tenants", perTenant);
        out.put("dispatcher", dispatcher.stats());
        return out;
    }

    /** Detail for ONE tenant only (an analyst for tenant A never sees tenant B's mailboxes). */
    @GetMapping("/remediations/{jobId}/tenants/{tenantId}")
    public List<MailboxResult> tenantDetail(@PathVariable String jobId, @PathVariable String tenantId) {
        tenants.get(tenantId);
        return service.job(jobId).forTenant(tenantId).values().stream()
                .sorted(Comparator.comparing(MailboxResult::mailbox)).toList();
    }

    /** Undo a false positive. */
    @PostMapping("/remediations/{jobId}/restore")
    public ResponseEntity<Map<String, Object>> restore(@PathVariable String jobId) {
        service.restore(jobId);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "restoring", true));
    }

    @GetMapping("/tenants/{tenantId}/audit")
    public List<AuditEntry> audit(@PathVariable String tenantId) {
        return service.auditFor(tenantId);
    }

    private static long count(java.util.Collection<MailboxResult> rs, MailboxStatus s) {
        return rs.stream().filter(r -> r.status() == s).count();
    }
}
