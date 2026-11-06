# Multi-tenant post-delivery email cleanup (Spring Boot demo)

My prototype of post-delivery threat response: an email is confirmed malicious *after*
delivery, so it must be pulled from every affected mailbox, across many customer tenants,
**quickly, fairly and safely**.

It is a prototype, not production code. Design: a shared core plus vendor adapters,
per-tenant configuration, retries on throttling, and auditability.

## Architecture

Two ways in, one action layer:

* **Alert-driven** (the main path): the detection stack posts threat alerts, one per
  threat, with status `ACTIVE`, `CLEARED` or `FALSE_POSITIVE`.
* **Analyst-driven**: "pull this campaign" by sender / subject (the original demo).

```text
 Detection stack ─► POST /api/alerts  (per threat: ACTIVE · CLEARED · FALSE_POSITIVE)
                         │
              ① IntakeQueue            one partition per customer, round-robin poll, ack after processing
                         │
                AlertNormalizer          normalise ids/addresses, dedup (tenant, threat, status, messages)
                         │
              ② ThreatStateStore       message ↔ threats, threat status, incidents  ──►  ④ ShardRouter
                         │                                                          customer → DB1..DB4
                RemediationDecider       any threat bad → quarantine; all cleared → restore     US·EU·IN·AU
                         │                (large actions held for approval)
                RecipientResolver        recipients + nested distribution lists + forwards
                         │
  POST /api/remediations ┤  (analyst campaign search joins here)
                         │
              ③ ActionRunner + TenantFairDispatcher
                    lane per customer × provider · per-lane + global caps · round-robin
                    429 → pause ONLY that lane, re-queue (escalating = circuit breaker)
                    errors → retry with back-off ── retries exhausted ──► ⑦ DeadLetterQueue (alert + replay)
                         │
                MailboxProvider connectors
          Graph (M365) · EWS (Exchange) · Gmail (Workspace) · Fake (offline)
                         │
              ⑥ Quarantine: Junk Email / Spam, never hard delete, undo
                         │
         audit trail per customer (in its shard, partitioned by month) · incident records

 Around the pipeline: ⑤ customer APIs (token bucket per customer, 429 + Retry-After) ·
                      metrics per customer · admin UI (/admin.html)
```

### Design components → code

| Design component | Code |
| --- | --- |
| Threat alerts per threat | `model/ThreatAlert`, `model/ThreatStatus`, `POST /api/alerts` (`ThreatResponseController`) |
| ① Intake queue, fair per customer | `intake/IntakeQueue` (partition per customer, round-robin, ack/nack redelivery) |
| Normalise + dedup | `intake/AlertNormalizer` |
| ② State store: message ↔ threats | `storage/ThreatStateStore`, `storage/MessageRecord`, `storage/IncidentRecord` |
| ④ Relational DB, routing table, regions | `storage/ShardRouter` (customer → shard, data-residency check), `storage/AuditLog` (monthly partitions) |
| Decision | `threat/RemediationDecider` |
| Resolve recipients | `threat/RecipientResolver` (nested lists, forwards, tenant-only) |
| ③ Action queues: lane per customer × provider | `service/TenantFairDispatcher` + `service/ActionRunner` |
| 429 → back off, re-queue, never block others | `TenantFairDispatcher.retryLater` (lane pause, escalation, `recordSuccess` resets) |
| Mailbox connectors | `GraphMailboxProvider`, `ExchangeMailboxProvider` (EWS), `GmailMailboxProvider`, `FakeMailboxProvider` |
| App-only token per customer tenant | `GraphMailboxProvider.token` (client credentials, cached per tenant) |
| ⑥ Quarantine, undo, approval for large actions | providers move to Junk/Spam; restore on all-cleared; `POST .../incidents/{threatId}/approve` |
| ⑦ Dead-letter queue | `service/DeadLetterQueue`, `GET/POST /api/tenants/{t}/dead-letters[/{id}/replay]` |
| Results + audit trail, incident records | `GET /api/tenants/{t}/audit`, `/incidents`, `/messages` |
| ⑤ Customer APIs, rate limit per customer | `web/CustomerRateLimiter` + `web/RateLimitInterceptor` on `/api/tenants/**` |
| Metrics per customer | `metrics/TenantMetrics`, `GET /api/tenants/{t}/metrics`, `GET /api/metrics` |
| Admin UI + login service | `static/admin.html`; login is an external dependency, not part of the demo |

### API

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/alerts` | Threat alerts in (enqueue only, 202) |
| GET | `/api/tenants/{t}/incidents[/{threatId}]` | incidents, with message and mailbox state |
| POST | `/api/tenants/{t}/incidents/{threatId}/approve` | release a held large action |
| GET | `/api/tenants/{t}/messages[/{messageId}]` | per-message status and threats |
| GET | `/api/tenants/{t}/metrics` · `/api/metrics` | queue depth/age, p95 time to quarantine, 429 rate |
| GET/POST | `/api/tenants/{t}/dead-letters` · `/{id}/replay` | dead-letter queue |
| GET | `/api/routing` | customer → shard → region, audit partitions |
| POST | `/api/remediations` · `/{job}/restore` | analyst campaign pull and undo |
| GET | `/api/tenants/{t}/audit` | audit trail for one customer |

### Original requirements → code

| Requirement | Where it is in the code |
| --- | --- |
| Tenant isolation | `TenantConfig` holds credentials per tenant; state and audit live in the tenant's shard; every API is per tenant |
| Fair resource use, parallel execution | `IntakeQueue` and `TenantFairDispatcher`: per-customer queues/lanes, round-robin, caps |
| Microsoft 365 / Exchange / Google Workspace | `GraphMailboxProvider`, `ExchangeMailboxProvider`, `GmailMailboxProvider` behind `MailboxProvider` |
| Provider throttling | `ThrottledException` + `Retry-After`; lane pause + re-queue (simulated 429 on `user5@bigbank`) |
| Safe automation | Quarantine, never hard-delete; empty criteria refused; approval threshold; restore undo |
| Idempotency | Dedup of repeated alerts; one action per mailbox copy in flight; re-runs find nothing left (`NOT_FOUND`) |

## Run the offline demo (no accounts needed)

Needs **JDK 21** (virtual threads) and Maven.

```bash
mvn spring-boot:run
./demo.sh            # in a second terminal
```

What to point out:

1. After 1 second, **smallclinic (3 mailboxes) is finished** while **bigbank (40)** is still
   running. Without the fair dispatcher, smallclinic would wait behind all 40.
2. The log shows a `429` for `user5@bigbank.example`, a back-off, then success.
3. `dispatcher.peakRunning` never exceeds each tenant's `limit` (protects that tenant's API quota).
4. `/remediations/{job}/tenants/smallclinic` only ever shows smallclinic mailboxes.
5. `restore` puts every message back: recovery from a false positive.
6. Alert flow: one message with two threats is quarantined in alice, bob and carol (resolved from the
   nested `all-staff` list). Clearing one threat keeps it quarantined; marking the second a false
   positive restores it. The repeated `url-1 ACTIVE` alert is deduplicated.
7. Open `http://localhost:8080/admin.html` for incidents, messages, metrics, dead letters and routing.

Tests: `mvn test` (unit tests per component, plus end-to-end flows for campaign pull, alert pipeline,
rate limiting and approval on the fake provider).

## Use your two real mailboxes (two tenants)

The `real` profile treats your Microsoft account and your Gmail account as **two separate
tenants**, each with its own token. The demo uses delegated tokens for your own account,
which last about an hour.

1. **Microsoft 365 / Outlook token:** open
   [Graph Explorer](https://developer.microsoft.com/graph/graph-explorer), sign in, use
   *Modify permissions* to consent to **Mail.ReadWrite**, then copy the token from the
   *Access token* tab.
2. **Gmail token:** open the
   [OAuth 2.0 Playground](https://developers.google.com/oauthplayground), enter the scope
   `https://www.googleapis.com/auth/gmail.modify`, authorise, choose *Exchange authorization
   code for tokens*, and copy the **access token**.
3. Send yourself a fake phishing email to **both** accounts, for example subject
   `DEMO invoice overdue - action required`.
4. Run:

```bash
export M365_TOKEN='paste-graph-token'
export GMAIL_TOKEN='paste-gmail-token'
mvn spring-boot:run -Dspring-boot.run.profiles=real
```

5. In another terminal:

```bash
curl -s localhost:8080/api/tenants/tenant-m365/mailboxes/me/inbox
curl -s localhost:8080/api/tenants/tenant-google/mailboxes/me/inbox

curl -s -X POST localhost:8080/api/remediations -H 'Content-Type: application/json' \
     -d '{"subjectContains":"DEMO invoice overdue"}'
# -> {"jobId":"abc12345", ...}

curl -s localhost:8080/api/remediations/abc12345
curl -s -X POST localhost:8080/api/remediations/abc12345/restore   # undo
```

The email moves to **Junk Email** in Outlook and to **Spam** in Gmail. Restore moves it back.
Keep tokens out of git and out of screenshots.

## How production would differ

- **Auth:** M365 uses an app-only token per customer tenant (client credentials, already
  sketched in `GraphMailboxProvider`), with mailbox access scoped by policy. Google Workspace
  uses a service account with domain-wide delegation per customer.
- **Durable work:** the in-memory `IntakeQueue` and action lanes become Kafka or SQS keyed by tenant.
  An action is committed only after the provider confirms the move (acknowledging earlier can
  lose work if a worker crashes); the demo already acks intake only after state is stored.
- **Finding messages:** search by Internet-Message-ID and hashes, follow forwards and expand
  distribution lists; use Graph `$batch` and paging instead of reading the 50 newest messages.
- **Throttling:** done in the demo (lane pause + re-queue + escalating breaker); production keeps
  the breaker state in Redis so all workers share it.
- **State:** `ShardRouter` points at a DataSource per shard instead of in-memory stores;
  PostgreSQL tables keyed by tenant first, audit partitioned by month, status published with a
  transactional outbox. Dedup "last seen" moves into the state store so it survives restarts.
- **Observability:** `TenantMetrics` becomes Micrometer timers/counters tagged by tenant.
- **Customer API limits and login:** token bucket moves to the API gateway; admin UI signs in via
  the company login service (SSO).
