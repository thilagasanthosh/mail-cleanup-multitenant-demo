package dev.demo.mailcleanup.web;

import dev.demo.mailcleanup.provider.FakeMailboxProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer APIs: per-customer rate limit, and approval for very large actions. */
@SpringBootTest(properties = {"demo.fake-latency-ms=1", "demo.approval-threshold=2",
        "demo.api-rate-limit.capacity=3", "demo.api-rate-limit.refill-per-second=1"})
@AutoConfigureMockMvc
class CustomerApiTest {

    @Autowired MockMvc mvc;

    @Test
    void rateLimitsPerCustomerWith429AndRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) mvc.perform(get("/api/tenants/retailco/incidents")).andExpect(status().isOk());
        mvc.perform(get("/api/tenants/retailco/incidents"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));
        // Another customer still has its own budget.
        mvc.perform(get("/api/tenants/bigbank/incidents")).andExpect(status().isOk());
    }

    @Test
    void largeActionWaitsForApproval() throws Exception {
        String alert = """
                [{"tenantId":"smallclinic","threatId":"url-big","status":"ACTIVE",
                  "messages":[{"messageId":"<big-1>","sender":"%s","subject":"%s",
                               "recipients":["all-staff@smallclinic.example"]}]}]"""
                .formatted(FakeMailboxProvider.PHISH_SENDER, FakeMailboxProvider.PHISH_SUBJECT);
        mvc.perform(post("/api/alerts").contentType(MediaType.APPLICATION_JSON).content(alert))
                .andExpect(status().isAccepted());

        // 3 mailboxes > threshold 2: held, nothing quarantined yet.
        awaitJson("/api/tenants/smallclinic/incidents/url-big", "$.incident.awaitingApproval", true);

        mvc.perform(post("/api/tenants/smallclinic/incidents/url-big/approve")).andExpect(status().isOk());
        awaitJson("/api/tenants/smallclinic/incidents/url-big",
                "$.messages[0].mailboxes['carol@smallclinic.example'].status", "QUARANTINED");
    }

    private void awaitJson(String url, String path, Object expected) throws Exception {
        AssertionError last = null;
        for (int i = 0; i < 100; i++) {
            try {
                mvc.perform(get(url)).andExpect(jsonPath(path).value(expected));
                return;
            } catch (AssertionError | Exception e) {
                last = e instanceof AssertionError a ? a : new AssertionError(e);
                Thread.sleep(50);
            }
        }
        throw last;
    }
}
