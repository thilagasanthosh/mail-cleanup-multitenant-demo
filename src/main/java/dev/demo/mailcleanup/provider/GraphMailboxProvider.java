package dev.demo.mailcleanup.provider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.model.FoundMessage;
import dev.demo.mailcleanup.model.ProviderType;
import dev.demo.mailcleanup.model.SearchCriteria;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Microsoft 365 via Microsoft Graph.
 *  - Demo: mailbox "me" + a delegated token from Graph Explorer (works for your own Outlook/M365 account).
 *  - Production: app-only token per customer tenant (client credentials), Mail.ReadWrite application
 *    permission, scoped to the right mailboxes, and mailbox paths /users/{upn}.
 * Quarantine = move to Junk Email (reversible). Graph returns a NEW id after a move; we keep it for undo.
 */
@Component
public class GraphMailboxProvider implements MailboxProvider {

    private static final String GRAPH = "https://graph.microsoft.com/v1.0";

    private record CachedToken(String value, Instant expiresAt) { }

    private final RestClient http = RestClient.create();
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    @Override public ProviderType type() { return ProviderType.M365; }

    @Override
    public List<FoundMessage> find(TenantConfig tenant, String mailbox, SearchCriteria criteria) {
        // Demo keeps it simple: read the 50 newest inbox messages and match locally.
        // Production: $filter on internetMessageId / from, paging via @odata.nextLink, and $batch.
        URI uri = UriComponentsBuilder.fromUriString(GRAPH)
                .pathSegment(userSegments(mailbox))
                .pathSegment("mailFolders", "inbox", "messages")
                .queryParam("$top", 50)
                .queryParam("$select", "id,subject,from")
                .encode().build().toUri();
        JsonNode body = http.get().uri(uri)
                .header("Authorization", "Bearer " + token(tenant))
                .retrieve()
                .onStatus(s -> s.value() == 429, (req, res) -> {
                    throw new ThrottledException(ThrottledException.parseRetryAfter(res.getHeaders().getFirst("Retry-After")));
                })
                .body(JsonNode.class);

        List<FoundMessage> found = new ArrayList<>();
        for (JsonNode m : body.path("value")) {
            String from = m.path("from").path("emailAddress").path("address").asText("");
            String subject = m.path("subject").asText("");
            if (criteria.isEmpty() || criteria.matches(from, subject)) {
                found.add(new FoundMessage(m.path("id").asText(), from, subject));
            }
        }
        return found;
    }

    @Override
    public String quarantine(TenantConfig tenant, String mailbox, String messageId) {
        return move(tenant, mailbox, messageId, "junkemail");
    }

    @Override
    public void restore(TenantConfig tenant, String mailbox, String quarantinedId) {
        move(tenant, mailbox, quarantinedId, "inbox");
    }

    private String move(TenantConfig tenant, String mailbox, String messageId, String destination) {
        URI uri = UriComponentsBuilder.fromUriString(GRAPH)
                .pathSegment(userSegments(mailbox))
                .pathSegment("messages", messageId, "move")
                .encode().build().toUri();
        JsonNode moved = http.post().uri(uri)
                .header("Authorization", "Bearer " + token(tenant))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("destinationId", destination))
                .retrieve()
                .onStatus(s -> s.value() == 429, (req, res) -> {
                    throw new ThrottledException(ThrottledException.parseRetryAfter(res.getHeaders().getFirst("Retry-After")));
                })
                .body(JsonNode.class);
        return moved.path("id").asText(messageId);
    }

    private static String[] userSegments(String mailbox) {
        return "me".equalsIgnoreCase(mailbox) ? new String[]{"me"} : new String[]{"users", mailbox};
    }

    /** Static demo token, or a cached app-only token per customer tenant. */
    private String token(TenantConfig tenant) {
        if (tenant.accessToken() != null && !tenant.accessToken().isBlank()) return tenant.accessToken();
        if (tenant.azureTenantId() == null || tenant.clientId() == null || tenant.clientSecret() == null) {
            throw new IllegalStateException("Tenant " + tenant.id() + " has no M365 access token or client credentials");
        }
        CachedToken cached = tokens.get(tenant.id());
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.value();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", tenant.clientId());
        form.add("client_secret", tenant.clientSecret());
        form.add("scope", "https://graph.microsoft.com/.default");
        JsonNode res = http.post()
                .uri("https://login.microsoftonline.com/" + tenant.azureTenantId() + "/oauth2/v2.0/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, r) -> {
                    throw new IllegalStateException("Token request failed for tenant " + tenant.id() + ": " + r.getStatusCode());
                })
                .body(JsonNode.class);
        String value = res.path("access_token").asText();
        long expiresIn = res.path("expires_in").asLong(3600);
        tokens.put(tenant.id(), new CachedToken(value, Instant.now().plusSeconds(expiresIn - 60)));
        return value;
    }
}
