# Notification delivery ledger

Every message JalSoochak sends goes out through a third-party provider: WhatsApp, email (SendGrid or
SMTP) or SMS (SMSCountry). The **delivery ledger** keeps a durable record of each one. It records who
the message was for, what it was, which provider took it, what the provider answered at send time, and
what the provider later reported happened to it.

The ledger is vendor-neutral by construction. Provider names live only in the adapters. Every adapter
reports its own identifier, and the ledger stores that identifier without constraining it, so adding a
provider needs no migration. Each provider's status vocabulary is mapped onto one neutral set of states,
and the provider's own word is kept alongside.

## Where it lives

| Table | Written by | Purpose |
| --- | --- | --- |
| `<tenant>.notification_table` | message-service | One row per message per recipient, in the ledger shape V63 gives it |
| `common_schema.notification_table` | message-service | The same shape, for sends that belong to no tenant: super-user invites and password resets |
| `common_schema.notification_status_sync_state` | message-service | The cursor for the incremental WhatsApp status pull |
| `analytics_schema.fact_notification_delivery_table` | analytics-service | One row per notification, upserted from the change feed |
| `analytics_schema.agg_notification_delivery_daily_table` | analytics-service | Per tenant, day, type, channel, provider and role: sent, delivered, read, failed, pending and more |
| `analytics_schema.agg_notification_failure_daily_table` | analytics-service | Failures per tenant, day, stage and provider error code |

The shape is applied by one idempotent function, `common_schema.apply_notification_ledger_shape(schema)`
(V63). It runs on every existing tenant, on the platform table and on every new tenant through the
`create_tenant_schema` wrapper.

**No personal data is stored.** A recipient is held as a `user_table` id, an admin-user id, or an HMAC
of the normalised phone number or email address (the same keyed hash `user_table.phone_number_hash`
uses). The ledger also never stores:

- **Message text or OTPs.** `message` is always NULL. `message_blob` holds only allow-listed metadata: delivery mode, template, a link without its query string, report dates.
- **Raw provider error text.** It is phone-redacted (and, for email, address-scrubbed) before it is stored.

The analytics feed carries no address, hash, contact id or error text at all.

## Two status axes

| Column | Meaning | Values |
| --- | --- | --- |
| `dispatch_status` | Our attempt to hand the message over | `DISPATCHING`, `ACCEPTED`, `SUPPRESSED` (dry-run), `PROVIDER_REJECTED`, `FAILED_DELIVERY`, `DELIVERY_UNCONFIRMED` (timeout, or accepted without an id), `FAILED_GENERATION`, `FAILED_UPLOAD`, `SKIPPED_NO_CONTACT` |
| `delivery_status` | What the provider later reports | `PENDING`, `DELIVERED`, `READ`, `FAILED`, `UNRESOLVED` (no final word within 72 h), `NOT_TRACKED` (no way to ask: SMTP, or a WhatsApp flow start with no message id), `NOT_SENT` |

`ACCEPTED` is what the router's logs call `result=SENT`. It means the provider took the message, not
that it was delivered.

`delivery_status` only moves forward:

- `PENDING` → `DELIVERED` → `READ`
- `PENDING` → `FAILED`

An `UNRESOLVED` or `NOT_TRACKED` row can still be settled by a late report. Each change bumps
`status_version`.

## How a row is written

1. **Open.** Once a send path knows its recipient, it calls `NotificationLedger.open`, before it calls
   the provider. This has three consequences:
   - A provider report can never arrive before its row exists.
   - A process that dies mid-send leaves a visible `DISPATCHING` row.
   - A report that fails to build or upload is recorded on the same row (`FAILED_GENERATION`, `FAILED_UPLOAD`).
2. **Close.** The send's outcome is recorded with the provider's message id, its own word for the
   acceptance, and the latency from opening the row to the provider's answer.
3. **Skip.** A recipient with nothing to send to is recorded directly as `SKIPPED_NO_CONTACT`.

**The ledger never throws into a send.** A database outage or a missing migration costs a WARN and a
`notification.ledger.write.failures` count. Sending, retries, dead-letter topics, dry-run gates and every
existing `result=` log line behave exactly as before.

### Which events are recorded

`NotificationEventRouterTest` fails the build if an event in `route()` is neither recorded nor listed as
sending nothing.

| Event | Type | Channel | Provider message id |
| --- | --- | --- | --- |
| `NUDGE` | `NUDGE` | WhatsApp | none: a flow start → `NOT_TRACKED` |
| `ESCALATION` | `ESCALATION` | WhatsApp | yes |
| `DAILY_REPORT_KPIS` / `WEEKLY_REPORT_KPIS` | `DAILY_REPORT` / `WEEKLY_REPORT` | WhatsApp | yes |
| `SEND_WELCOME_MESSAGE(_ADMIN)` | `WELCOME` | WhatsApp | none: a flow start → `NOT_TRACKED` |
| `SEND_LOGIN_OTP` | `LOGIN_OTP` | WhatsApp or SMS | yes |
| `SEND_INVITE_EMAIL` / `SEND_REINVITE_EMAIL` / `SEND_PASSWORD_RESET_EMAIL` | `INVITE` / `REINVITE` / `PASSWORD_RESET` | Email | SendGrid: yes. SMTP: none → `NOT_TRACKED` |
| `STAFF_SYNC_COMPLETED`, `UPDATE_USER_LANGUAGE` | not recorded | | they send nothing |

## How delivery status arrives

| Provider | Pull | Push |
| --- | --- | --- |
| WhatsApp | Every reconcile pass (`WhatsAppDeliveryReconciliationService` → `WhatsAppLedgerStatusSync`), in three steps: **(1)** every outbound template message in the send-time window; **(2)** an incremental read of every status change since the persisted `updated_at` cursor; **(3)** a per-message lookup for rows still pending after 2 h, capped per pass | none |
| SMS | `NotificationStatusSweepService`, every 15 min: asks the account that sent each pending row (`GET …/SMSes/{MessageUUID}/`). This is the main SMS path | optional delivery-report callback |
| Email | the same sweep, through the Email Activity API, **only if** `NOTIFICATIONS_EMAIL_STATUS_LOOKUP_ENABLED` (a paid SendGrid add-on) | SendGrid Signed Event Webhook (main email path) |

Rows still `PENDING` after `notifications.ledger.status.lookback-hours` (72) become `UNRESOLVED`.

### Reading WhatsApp status completely

The WhatsApp provider returns at most 50 messages per page, whatever page size is asked for. The status
reader works with that:

- **Real page size.** It pages in 50s and treats only a page shorter than 50 as the last one.
- **Count-sized budget.** It sizes its page budget from the provider's own count: `ceil(count/50) + 1`, capped by `whatsapp.status.reconcile.max-pages-cap`.
- **Visible truncation.** It logs `count=` against `fetched=` per status, with `complete=false` whenever it read fewer than counted.

Statuses are read in the order a message moves through them, so a message that advances mid-pass lands
in a status not yet read. A message seen under two statuses is counted once, at its most advanced status.

### Push endpoint

`POST /api/v1/message/delivery-receipts/{providerId}`

This endpoint is open at the gateway and in message-service's security config. Providers cannot
present our JWTs, so each `DeliveryReceiptAdapter` authenticates its own provider before it reads
anything:

- **SendGrid** (`/delivery-receipts/sendgrid`): ECDSA signature over timestamp + raw body, verified
  against `NOTIFICATIONS_EMAIL_WEBHOOK_VERIFICATION_KEYS`. That setting is comma-separated: one public
  key per SendGrid account, so a tenant's own account adds its key. Events carry our row reference in
  `custom_args.ledger_ref`, so a report goes straight to its schema and row.
- **SMSCountry** (`/delivery-receipts/smscountry`): a shared `token` in the callback URL, checked by
  SHA-256 against `NOTIFICATIONS_SMS_WEBHOOK_TOKEN_HASHES`. Our row reference rides as `ref`.

Both adapters support `…_MODE=ENFORCE` (the default), `AUDIT` and `OFF`. With no key or hash configured,
`ENFORCE` refuses every request.

The endpoint answers:

| Status | When |
| --- | --- |
| 404 | unknown provider |
| 401 | unauthenticated |
| 400 | unreadable |
| 413 | body over 2 MB |
| 200 | otherwise, including when no row matched |

## Turning it on, per environment

1. **Apply V63** with the usual migration job (`backend/database/migrate.sh`). V63 refuses to reshape a
   `notification_table` that already has rows, rather than lose or misread them.
2. **Deploy message-service, user-service and analytics-service.** analytics-service runs V59 itself.
   user-service sends `userId` on OTP events and `adminUserId` (and `tenantId` on invites) on email
   events. Each side tolerates the other being older.
3. Set **`NOTIFICATIONS_LEDGER_ENABLED=true`** on message-service. WhatsApp status also needs
   `WHATSAPP_STATUS_RECONCILE_ENABLED=true`.
4. **Email push:** in each SendGrid account, enable the Event Webhook with these settings:
   - **URL:** `https://<public-host>/api/v1/message/delivery-receipts/sendgrid`
   - **Events:** processed, delivered, deferred, bounce, dropped, open
   - **Signed Event Webhook:** on

   Then set `NOTIFICATIONS_EMAIL_WEBHOOK_VERIFICATION_KEYS` to the public key(s) SendGrid shows.
5. **SMS push (optional):** set `NOTIFICATIONS_SMS_DELIVERY_REPORT_URL` to
   `https://<public-host>/api/v1/message/delivery-receipts/smscountry?token=<secret>`. Set
   `NOTIFICATIONS_SMS_WEBHOOK_TOKEN_HASHES` to `sha256(<secret>)`, as lowercase hex. Leaving both unset
   is fine: the sweep reads SMS status anyway.

## Useful queries

```sql
-- One officer's messages, newest first
SELECT message_type, dispatch_status, delivery_status, provider_status, provider_error_code,
       created_at, delivered_at, read_at
FROM tenant_mp.notification_table WHERE user_id = 16714 ORDER BY created_at DESC LIMIT 20;

-- Yesterday's daily reports, by outcome
SELECT delivery_status, COUNT(*) FROM tenant_mp.notification_table
WHERE message_type = 'DAILY_REPORT' AND subject_date = CURRENT_DATE - 1 GROUP BY 1;

-- Cross-tenant daily statistics (analytics)
SELECT * FROM analytics_schema.agg_notification_delivery_daily_table
WHERE stat_date = CURRENT_DATE - 1 ORDER BY tenant_id, message_type;
```

A `[NotificationStats]` line per tenant, type, channel and provider is logged every morning for the
previous IST day: sent, accepted, delivered (including read), read, failed, pending, unresolved, untracked
and not sent.

## Configuration

| Property | Env var | Default |
| --- | --- | --- |
| `notifications.ledger.enabled` | `NOTIFICATIONS_LEDGER_ENABLED` | `false` |
| `notifications.ledger.retention-days` | `NOTIFICATIONS_LEDGER_RETENTION_DAYS` | `180` |
| `notifications.ledger.events.enabled` | `NOTIFICATIONS_LEDGER_EVENTS_ENABLED` | `true` |
| `notifications.ledger.status.lookback-hours` | `NOTIFICATIONS_LEDGER_STATUS_LOOKBACK_HOURS` | `72` |
| `notifications.ledger.status.sweep-after-minutes` (WhatsApp) | `…_SWEEP_AFTER_MINUTES` | `120` |
| `notifications.ledger.status.email-sms-sweep-after-minutes` | `…_EMAIL_SMS_SWEEP_AFTER_MINUTES` | `5` |
| `notifications.ledger.stats.cron` | `NOTIFICATIONS_LEDGER_STATS_CRON` | `0 30 9 * * *` (IST) |
| `notifications.email.webhook.verification-keys` / `.mode` | `NOTIFICATIONS_EMAIL_WEBHOOK_*` | empty / `ENFORCE` |
| `notifications.email.status-lookup.enabled` | `NOTIFICATIONS_EMAIL_STATUS_LOOKUP_ENABLED` | `false` |
| `notifications.sms.delivery-report.url` | `NOTIFICATIONS_SMS_DELIVERY_REPORT_URL` | empty |
| `notifications.sms.webhook.token-hashes` / `.mode` | `NOTIFICATIONS_SMS_WEBHOOK_*` | empty / `ENFORCE` |
| `whatsapp.status.reconcile.max-pages-cap` | `WHATSAPP_STATUS_RECONCILE_MAX_PAGES_CAP` | `400` |
| `whatsapp.status.ledger.incremental.enabled` | `WHATSAPP_STATUS_LEDGER_INCREMENTAL_ENABLED` | `true` |
| `whatsapp.status.ledger.sweep-max-per-pass` | `WHATSAPP_STATUS_LEDGER_SWEEP_MAX_PER_PASS` | `200` |

analytics-service settings:

- `analytics.notification-delivery.aggregation.cron`: default hourly, at :15.
- `analytics.notification-delivery.aggregation.lookback-days`: default `3`.
- `analytics.notification-delivery.account-level-error-codes`: default `9999`.

## Not done yet

- **Nudge and welcome flows have no message id.** Their rows stay `NOT_TRACKED`; tying them to a provider message by contact, template and time is a follow-up.
- **SMTP bounces** arrive as mail to the sender address. Reading that mailbox is not built.
- **`dedupe_key`** (`TYPE:recipient:subject_date`) is recorded, but nothing enforces it.
- **No read API** over the ledger or the analytics tables yet.
