package dev.demo.mailcleanup.provider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.model.FoundMessage;
import dev.demo.mailcleanup.model.ProviderType;
import dev.demo.mailcleanup.model.SearchCriteria;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Workspace / Gmail via the Gmail REST API.
 *  - Demo: mailbox "me" + a delegated token with scope https://www.googleapis.com/auth/gmail.modify
 *    (Google OAuth 2.0 Playground works for a personal Gmail account).
 *  - Production: a service account with domain-wide delegation per Workspace customer,
 *    impersonating each user (userId = their address).
 * Quarantine = remove INBOX label, add SPAM label (reversible). Gmail ids do not change.
 */
@Component
public class GmailMailboxProvider implements MailboxProvider {

    private static final String GMAIL = "https://gmail.googleapis.com/gmail/v1/users";

    private final RestClient http = RestClient.create();

    @Override public ProviderType type() { return ProviderType.GOOGLE; }

    @Override
    public List<FoundMessage> find(TenantConfig tenant, String mailbox, SearchCriteria criteria) {
        StringBuilder q = new StringBuilder("in:inbox");
        if (criteria.sender() != null && !criteria.sender().isBlank()) q.append(" from:").append(criteria.sender().trim());
        if (criteria.subjectContains() != null && !criteria.subjectContains().isBlank()) {
            q.append(" subject:(\"").append(criteria.subjectContains().trim()).append("\")");
        }
        URI listUri = UriComponentsBuilder.fromUriString(GMAIL)
                .pathSegment(mailbox, "messages")
                .queryParam("q", q.toString())
                .queryParam("maxResults", 50)
                .encode().build().toUri();
        JsonNode list = get(tenant, listUri);

        List<FoundMessage> found = new ArrayList<>();
        for (JsonNode ref : list.path("messages")) {
            String id = ref.path("id").asText();
            URI metaUri = UriComponentsBuilder.fromUriString(GMAIL)
                    .pathSegment(mailbox, "messages", id)
                    .queryParam("format", "metadata")
                    .queryParam("metadataHeaders", "From", "Subject")
                    .encode().build().toUri();
            JsonNode meta = get(tenant, metaUri);
            String from = "", subject = "";
            for (JsonNode h : meta.path("payload").path("headers")) {
                if ("From".equalsIgnoreCase(h.path("name").asText())) from = extractAddress(h.path("value").asText());
                if ("Subject".equalsIgnoreCase(h.path("name").asText())) subject = h.path("value").asText();
            }
            // Gmail search is fuzzy; re-check exactly so we never quarantine the wrong message.
            if (criteria.isEmpty() || criteria.matches(from, subject)) found.add(new FoundMessage(id, from, subject));
        }
        return found;
    }

    @Override
    public String quarantine(TenantConfig tenant, String mailbox, String messageId) {
        modify(tenant, mailbox, messageId, List.of("SPAM"), List.of("INBOX"));
        return messageId;
    }

    @Override
    public void restore(TenantConfig tenant, String mailbox, String quarantinedId) {
        modify(tenant, mailbox, quarantinedId, List.of("INBOX"), List.of("SPAM"));
    }

    private void modify(TenantConfig tenant, String mailbox, String id, List<String> add, List<String> remove) {
        URI uri = UriComponentsBuilder.fromUriString(GMAIL)
                .pathSegment(mailbox, "messages", id, "modify").encode().build().toUri();
        http.post().uri(uri)
                .header("Authorization", "Bearer " + token(tenant))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("addLabelIds", add, "removeLabelIds", remove))
                .retrieve()
                .onStatus(s -> s.value() == 429, (req, res) -> {
                    throw new ThrottledException(ThrottledException.parseRetryAfter(res.getHeaders().getFirst("Retry-After")));
                })
                .toBodilessEntity();
    }

    private JsonNode get(TenantConfig tenant, URI uri) {
        return http.get().uri(uri)
                .header("Authorization", "Bearer " + token(tenant))
                .retrieve()
                .onStatus(s -> s.value() == 429, (req, res) -> {
                    throw new ThrottledException(ThrottledException.parseRetryAfter(res.getHeaders().getFirst("Retry-After")));
                })
                .body(JsonNode.class);
    }

    private static String token(TenantConfig tenant) {
        if (tenant.accessToken() == null || tenant.accessToken().isBlank()) {
            throw new IllegalStateException("Tenant " + tenant.id() + " has no Gmail access token");
        }
        return tenant.accessToken();
    }

    /** "Name <a@b.com>" -> "a@b.com" */
    private static String extractAddress(String header) {
        int lt = header.indexOf('<'), gt = header.indexOf('>');
        return (lt >= 0 && gt > lt) ? header.substring(lt + 1, gt).trim() : header.trim();
    }
}
