package dev.demo.mailcleanup.provider;

import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.model.FoundMessage;
import dev.demo.mailcleanup.model.ProviderType;
import dev.demo.mailcleanup.model.SearchCriteria;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * On-premises / hybrid Exchange via Exchange Web Services (SOAP).
 *  - Auth: bearer token (Exchange Online OAuth, or an on-prem OAuth/hybrid token) per customer tenant.
 *  - Mailbox access: the service account needs ApplicationImpersonation; we send ExchangeImpersonation
 *    so each call acts as that user.
 * Quarantine = MoveItem to Junk Email (reversible). Like Graph, EWS returns a NEW ItemId after a move.
 */
@Component
public class ExchangeMailboxProvider implements MailboxProvider {

    private static final String T = "http://schemas.microsoft.com/exchange/services/2006/types";

    private final RestClient http = RestClient.create();

    @Override public ProviderType type() { return ProviderType.EXCHANGE; }

    @Override
    public List<FoundMessage> find(TenantConfig tenant, String mailbox, SearchCriteria criteria) {
        String restriction = criteria.subjectContains() == null || criteria.subjectContains().isBlank() ? "" : """
                <m:Restriction>
                  <t:Contains ContainmentMode="Substring" ContainmentComparison="IgnoreCase">
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:Constant Value="%s"/>
                  </t:Contains>
                </m:Restriction>""".formatted(xml(criteria.subjectContains().trim()));
        String body = """
                <m:FindItem Traversal="Shallow">
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="item:Subject"/>
                      <t:FieldURI FieldURI="message:From"/>
                    </t:AdditionalProperties>
                  </m:ItemShape>
                  <m:IndexedPageItemView MaxEntriesReturned="50" Offset="0" BasePoint="Beginning"/>
                  %s
                  <m:ParentFolderIds><t:DistinguishedFolderId Id="inbox"/></m:ParentFolderIds>
                </m:FindItem>""".formatted(restriction);
        Document doc = call(tenant, mailbox, body);

        List<FoundMessage> found = new ArrayList<>();
        NodeList messages = doc.getElementsByTagNameNS(T, "Message");
        for (int i = 0; i < messages.getLength(); i++) {
            Element m = (Element) messages.item(i);
            String id = attr(m, "ItemId", "Id");
            String subject = text(m, "Subject");
            String from = text(m, "EmailAddress");
            // EWS Contains is a substring match on subject only; re-check sender exactly.
            if (criteria.isEmpty() || criteria.matches(from, subject)) found.add(new FoundMessage(id, from, subject));
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

    private String move(TenantConfig tenant, String mailbox, String itemId, String folder) {
        String body = """
                <m:MoveItem>
                  <m:ToFolderId><t:DistinguishedFolderId Id="%s"/></m:ToFolderId>
                  <m:ItemIds><t:ItemId Id="%s"/></m:ItemIds>
                </m:MoveItem>""".formatted(folder, xml(itemId));
        Document doc = call(tenant, mailbox, body);
        NodeList ids = doc.getElementsByTagNameNS(T, "ItemId");
        return ids.getLength() == 0 ? itemId : ((Element) ids.item(0)).getAttribute("Id");
    }

    private Document call(TenantConfig tenant, String mailbox, String operation) {
        String envelope = """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:t="%s"
                               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <soap:Header>
                    <t:RequestServerVersion Version="Exchange2013_SP1"/>
                    <t:ExchangeImpersonation><t:ConnectingSID>
                      <t:SmtpAddress>%s</t:SmtpAddress>
                    </t:ConnectingSID></t:ExchangeImpersonation>
                  </soap:Header>
                  <soap:Body>%s</soap:Body>
                </soap:Envelope>""".formatted(T, xml(mailbox), operation);
        String response = http.post().uri(tenant.ewsUrl())
                .header("Authorization", "Bearer " + token(tenant))
                .header("X-AnchorMailbox", mailbox)
                .contentType(MediaType.TEXT_XML)
                .body(envelope)
                .retrieve()
                // EWS signals throttling as 503 (on-prem) or 429 (Exchange Online), with Retry-After when known.
                .onStatus(s -> s.value() == 429 || s.value() == 503, (req, res) -> {
                    throw new ThrottledException(ThrottledException.parseRetryAfter(res.getHeaders().getFirst("Retry-After")));
                })
                .body(String.class);
        Document doc = parse(response);
        NodeList codes = doc.getElementsByTagNameNS("http://schemas.microsoft.com/exchange/services/2006/messages", "ResponseCode");
        for (int i = 0; i < codes.getLength(); i++) {
            String code = codes.item(i).getTextContent();
            if ("ErrorServerBusy".equals(code)) throw new ThrottledException(java.time.Duration.ofSeconds(5));
            if (!"NoError".equals(code)) throw new IllegalStateException("EWS error for " + mailbox + ": " + code);
        }
        return doc;
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // no XXE
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable EWS response", e);
        }
    }

    private static String text(Element parent, String local) {
        NodeList n = parent.getElementsByTagNameNS(T, local);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().trim();
    }

    private static String attr(Element parent, String local, String attr) {
        NodeList n = parent.getElementsByTagNameNS(T, local);
        return n.getLength() == 0 ? "" : ((Element) n.item(0)).getAttribute(attr);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String token(TenantConfig tenant) {
        if (tenant.accessToken() == null || tenant.accessToken().isBlank()) {
            throw new IllegalStateException("Tenant " + tenant.id() + " has no Exchange access token");
        }
        return tenant.accessToken();
    }
}
