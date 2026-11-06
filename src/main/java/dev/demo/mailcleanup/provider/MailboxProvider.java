package dev.demo.mailcleanup.provider;

import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.model.FoundMessage;
import dev.demo.mailcleanup.model.ProviderType;
import dev.demo.mailcleanup.model.SearchCriteria;

import java.util.List;

/**
 * One interface for every mailbox platform (same idea as the ADR framework:
 * shared core + vendor adapters). Every call carries the tenant, so credentials stay per tenant.
 */
public interface MailboxProvider {

    ProviderType type();

    /** Inbox messages matching the criteria (empty criteria = list the inbox, read-only). */
    List<FoundMessage> find(TenantConfig tenant, String mailbox, SearchCriteria criteria);

    /** Move to quarantine (reversible, never hard-delete). Returns the message id after the move. */
    String quarantine(TenantConfig tenant, String mailbox, String messageId);

    /** Undo: move the message back to the inbox. */
    void restore(TenantConfig tenant, String mailbox, String quarantinedId);
}
