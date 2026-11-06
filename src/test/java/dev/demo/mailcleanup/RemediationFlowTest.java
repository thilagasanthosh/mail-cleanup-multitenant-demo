package dev.demo.mailcleanup;

import dev.demo.mailcleanup.model.MailboxStatus;
import dev.demo.mailcleanup.model.SearchCriteria;
import dev.demo.mailcleanup.provider.FakeMailboxProvider;
import dev.demo.mailcleanup.service.RemediationJob;
import dev.demo.mailcleanup.service.RemediationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "demo.fake-latency-ms=5")
class RemediationFlowTest {

    @Autowired RemediationService service;

    @Test
    void quarantinesCampaignAcrossTenantsThenRestores() throws Exception {
        RemediationJob job = service.start(new SearchCriteria(FakeMailboxProvider.PHISH_SENDER, "invoice overdue"),
                List.of(), false);
        waitUntil(() -> job.tenantIds().stream().allMatch(t ->
                job.forTenant(t).values().stream().noneMatch(r -> r.status() == MailboxStatus.PENDING)));

        // bigbank: 40 mailboxes, every 4th did not receive it -> 30 quarantined, 10 not found
        var big = job.forTenant("bigbank").values();
        assertThat(big.stream().filter(r -> r.status() == MailboxStatus.QUARANTINED)).hasSize(30);
        assertThat(big.stream().filter(r -> r.status() == MailboxStatus.NOT_FOUND)).hasSize(10);
        // user5 was throttled once (429) and succeeded on retry
        assertThat(job.forTenant("bigbank").get("user5@bigbank.example").attempts()).isEqualTo(2);
        // tenant isolation: smallclinic results only contain smallclinic mailboxes
        assertThat(job.forTenant("smallclinic").keySet()).allMatch(m -> m.endsWith("@smallclinic.example"));

        service.restore(job.id());
        waitUntil(() -> job.forTenant("bigbank").values().stream()
                .noneMatch(r -> r.status() == MailboxStatus.QUARANTINED));
        assertThat(service.auditFor("smallclinic")).allMatch(a -> a.tenantId().equals("smallclinic"));
    }

    @Test
    void refusesEmptyCriteria() {
        assertThatThrownBy(() -> service.start(new SearchCriteria(" ", null), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void waitUntil(java.util.function.BooleanSupplier ok) throws InterruptedException {
        for (int i = 0; i < 200 && !ok.getAsBoolean(); i++) Thread.sleep(50);
        assertThat(ok.getAsBoolean()).isTrue();
    }
}
