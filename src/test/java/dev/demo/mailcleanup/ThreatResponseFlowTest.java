package dev.demo.mailcleanup;

import dev.demo.mailcleanup.config.TenantRegistry;
import dev.demo.mailcleanup.model.AlertMessage;
import dev.demo.mailcleanup.model.SearchCriteria;
import dev.demo.mailcleanup.model.ThreatAlert;
import dev.demo.mailcleanup.model.ThreatStatus;
import dev.demo.mailcleanup.provider.FakeMailboxProvider;
import dev.demo.mailcleanup.storage.MessageRecord;
import dev.demo.mailcleanup.storage.MessageRecord.MailboxStatus;
import dev.demo.mailcleanup.storage.ShardRouter;
import dev.demo.mailcleanup.threat.ThreatResponseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** The alert-driven pipeline end to end on the fake provider. Own context: own fake mailboxes. */
@SpringBootTest(properties = "demo.fake-latency-ms=4")
class ThreatResponseFlowTest {

    private static final String TENANT = "smallclinic";
    private static final String MSG = "<phish-1@micros0ft-support.example>";
    private static final List<String> STAFF =
            List.of("alice@smallclinic.example", "bob@smallclinic.example", "carol@smallclinic.example");

    @Autowired ThreatResponseService service;
    @Autowired FakeMailboxProvider fake;
    @Autowired TenantRegistry tenants;
    @Autowired ShardRouter shards;

    private static ThreatAlert alert(String threatId, ThreatStatus status) {
        return new ThreatAlert(TENANT, threatId, status, List.of(new AlertMessage(MSG,
                FakeMailboxProvider.PHISH_SENDER, FakeMailboxProvider.PHISH_SUBJECT,
                List.of("all-staff@smallclinic.example"))));
    }

    @Test
    void quarantinesOnAnyBadThreatAndRestoresOnlyWhenAllAreCleared() throws Exception {
        // One message, two threats (malicious URL + malicious attachment), sent to a nested distribution list.
        service.submit(List.of(alert("url-1", ThreatStatus.ACTIVE), alert("att-1", ThreatStatus.ACTIVE)));
        waitUntil(() -> allCopies(MailboxStatus.QUARANTINED));
        assertThat(phishInInbox("alice@smallclinic.example")).isFalse();

        // Same verdict re-sent: deduplicated, no second round of provider work.
        service.submit(List.of(alert("url-1", ThreatStatus.ACTIVE)));

        // One threat cleared: the attachment is still bad, so the message stays quarantined.
        service.submit(List.of(alert("url-1", ThreatStatus.CLEARED)));
        waitUntil(() -> service.incident(TENANT, "url-1").status() == ThreatStatus.CLEARED);
        Thread.sleep(200);
        assertThat(allCopies(MailboxStatus.QUARANTINED)).isTrue();

        // Last threat is a false positive: every copy goes back to the inbox.
        service.submit(List.of(alert("att-1", ThreatStatus.FALSE_POSITIVE)));
        waitUntil(() -> allCopies(MailboxStatus.RESTORED));
        assertThat(phishInInbox("alice@smallclinic.example")).isTrue();
        assertThat(ThreatResponseService.overall(message())).isEqualTo(ThreatStatus.CLEARED); // mixed cleared + FP

        // Audit lives in the tenant's shard (EU) and only contains this tenant.
        assertThat(shards.forTenant(TENANT).region()).isEqualTo("EU");
        assertThat(shards.forTenant(TENANT).audit().forTenant(TENANT))
                .extracting(a -> a.action()).contains("THREAT_ACTIVE", "QUARANTINE", "RESTORE");
        // 3 copies quarantined once each: the duplicate alert caused no extra quarantine.
        assertThat(shards.forTenant(TENANT).audit().forTenant(TENANT))
                .filteredOn(a -> a.action().equals("QUARANTINE")).hasSize(3);
    }

    private MessageRecord message() { return service.message(TENANT, MSG); }

    private boolean allCopies(MailboxStatus status) {
        var copies = message().mailboxes();
        return copies.keySet().containsAll(STAFF) && copies.values().stream().allMatch(s -> s.status() == status);
    }

    private boolean phishInInbox(String mailbox) {
        return !fake.find(tenants.get(TENANT), mailbox,
                new SearchCriteria(FakeMailboxProvider.PHISH_SENDER, null)).isEmpty();
    }

    private static void waitUntil(BooleanSupplier ok) throws InterruptedException {
        for (int i = 0; i < 200 && !safe(ok); i++) Thread.sleep(50);
        assertThat(safe(ok)).isTrue();
    }

    private static boolean safe(BooleanSupplier ok) {
        try {
            return ok.getAsBoolean();
        } catch (RuntimeException notYet) {
            return false;
        }
    }
}
