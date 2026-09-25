# Per-Tenant Messaging Providers (Email & SMS)

**Scope:** how a tenant sends transactional email and login-OTP SMS through **its own provider
account** instead of the platform's, and how that account is configured, stored, resolved and
failed back from.

**Services:** `tenant-service` (write path), `message-service` (read path), `user-service`
(tenant identity on the events).

This is the per-tenant layer. The platform-wide layer underneath it — the `EmailSender` /
`SmsSender` ports and their vendor adapters — is documented in
[message-service-provider-pluggability.md](message-service-provider-pluggability.md) and is
unchanged in shape; what changed is that several accounts now coexist in one process instead of
one bean being selected at startup.

**Not included:** WhatsApp. Every tenant shares one WhatsApp provider account, so there is no
per-tenant WhatsApp credential to store. `WHATSAPP` is reserved as a string in the
`tenant_provider_secret.channel` column but is **not** a constant of the `MessagingChannel` enum
in either service, so it is not addressable through the API.

---

## 1. What it does

| | Before | Now |
| --- | --- | --- |
| Email account | One, from `notification.mail.*` | The tenant's own, or the platform's |
| SMS account | One, from `smscountry.*` | The tenant's own, or the platform's |
| Choosing it | `@ConditionalOnProperty` at startup | Resolved per message, cached |
| Credentials | Environment variables | Encrypted per tenant in the database |
| Who configures it | Ops, by redeploying | A state admin, through the API |

A tenant that configures nothing behaves exactly as before. So does every tenant while the
feature flag is off, which is the default.

**Feature flag:** `notification.per-tenant-providers.enabled`
(`NOTIFICATION_PER_TENANT_PROVIDERS_ENABLED`), default `false`, in `message-service`.

### Supported providers

| Channel | Provider | Wire name | Required secrets |
| --- | --- | --- | --- |
| EMAIL | SendGrid | `sendgrid` | `apiKey` |
| EMAIL | SMTP relay | `smtp` | `password` |
| SMS | SMSCountry | `smscountry` | `authKey`, `authToken` |

The wire names and the secret names are declared once per provider, on `EmailProviderType` /
`SmsProviderType`, and each service keeps its own copy of those enums (see §9).

---

## 2. Moving parts

```
 tenant-service (WRITE)                              message-service (READ)
 ─────────────────────                               ──────────────────────
 TenantMessagingProviderController                   NotificationEventRouter
   │  PUT  /messaging-providers                        │  (per message)
   │  PUT  /messaging-providers/{channel}/secrets      │
   ▼                                                   ▼
 MessagingProviderSettingsValidator                  TenantRefResolver
   │  provider ↔ block, SMTP allowlist,                │  id ↔ state_code
   │  TLS, DNS, OTP template                           ▼
   ▼                                                 TenantChannelProviders
 tenant_config_master_table ──────────────────────▶    │  flag, cache, fallback
   EMAIL_PROVIDER_SETTINGS                              │
   SMS_PROVIDER_SETTINGS                                ├─▶ TenantProviderConfigRepository
   │                                                    ├─▶ ProviderEndpointPolicy (SMTP)
 SecretCryptoService (wrap)                             ├─▶ TenantSecretResolver
   │  AES-256-GCM envelope                              │     └─▶ SecretCryptoService (unwrap)
   ▼                                                    ▼
 tenant_secret_key ───────────────────────────────▶  SendGridMailSenderFactory
 tenant_provider_secret ──────────────────────────▶  SmtpMailSenderFactory
   │                                                 SmsCountrySenderFactory
   │  TENANT_CONFIG_UPDATED                             │
   └─────────▶ tenant-service-topic ─────────────────▶ TenantConfigUpdatedListener
                                                        └─▶ evict(tenantId, channel)
```

---

## 3. Data model

### 3.1 Settings — ordinary config rows

Settings live in `common_schema.tenant_config_master_table` under two new
`TenantConfigKeyEnum` constants:

| Key | Type | `isPublic` | `managedValue` | `mandatory` |
| --- | --- | --- | --- | --- |
| `EMAIL_PROVIDER_SETTINGS` | `GENERIC` | false | **true** | false |
| `SMS_PROVIDER_SETTINGS` | `GENERIC` | false | **true** | false |

`managedValue = true` means the generic `PUT /api/v1/tenants/{id}/config` **cannot** write them.
They are reachable only through the dedicated endpoint, so a settings value can never skip the
cross-field validation in §4.2. `mandatory = false` keeps them out of the
ONBOARDED → CONFIGURED transition, since a tenant with no settings is a supported state.

A third key is a **system** config row, on tenant id `0`:

| Key | Type |
| --- | --- |
| `MESSAGING_PROVIDER_ALLOWED_HOSTS` | `SystemConfigKeyEnum`, written by a super user |

Stored as `{"smtp": ["smtp.mp.gov.in", "*.nic.in"]}`. Unset or empty means **no tenant may use
SMTP** — it fails closed.

### 3.2 Secrets — V44, two tables

`backend/database/V44__create_tenant_provider_secret_tables.sql`, both in `common_schema`
(`create_tenant_schema()` is deliberately untouched):

```
common_schema.tenant_secret_key
  (tenant_id, key_version) UNIQUE       → wrapped_key, master_key_id, status
  partial UNIQUE (tenant_id) WHERE status = 'ACTIVE'   -- at most one usable key

common_schema.tenant_provider_secret
  (tenant_id, channel, secret_name) UNIQUE → ciphertext, key_version
  composite FK (tenant_id, key_version) → tenant_secret_key
  partial index (tenant_id, channel) WHERE deleted_at IS NULL
```

Two details that are load-bearing:

- The unique constraint on `tenant_provider_secret` is **unconditional**, unlike
  `tenant_config_master_table`'s partial one. A soft-deleted secret must be *revived* by the next
  write of the same name rather than sitting beside a second live row, so the upsert's
  `ON CONFLICT` has to see deleted rows.
- The FK is **composite** (`tenant_id, key_version`), not just `tenant_id`. That is what makes an
  orphaned or cross-version `key_version` unstorable, which is what lets the AAD trust the row.

---

## 4. Write path — `tenant-service`

### 4.1 Endpoints

All under `/api/v1/tenants/{tenantId}/messaging-providers`, all `@RequiresTenantAccess`
(`SUPER_USER` may configure any tenant; `STATE_ADMIN` only the tenant matching their
`tenant_state_code` JWT claim):

| Method | Path | Does |
| --- | --- | --- |
| `PUT` | `/` | Store email and/or SMS settings. A channel left out keeps its current settings. |
| `GET` | `/` | Both channels' settings **plus** SET/MISSING per credential. |
| `DELETE` | `/{channel}` | Remove that channel's settings → tenant falls back to the system default. Secrets are left alone. |
| `PUT` | `/{channel}/secrets` | Encrypt and store the named credentials. Only names present are written. |
| `GET` | `/{channel}/secrets` | SET or MISSING per name. **Never a value.** |
| `DELETE` | `/{channel}/secrets` | Soft-delete every credential on the channel. |

Key rotation is separate and `SUPER_USER`-only, because rotating a key is an operational response
to a suspected exposure and must not be reachable by the state admin whose credentials may be the
ones that leaked:

| Method | Path | Does |
| --- | --- | --- |
| `POST` | `/api/v1/system/messaging-secrets/rewrap` | Re-wrap every tenant data key under the active master key. |
| `POST` | `/api/v1/system/messaging-secrets/tenants/{tenantId}/rotate` | Issue one tenant a new data key and re-encrypt its secrets. |

Settings and secrets are stored separately but **neither is usable alone**, which is why the
collection `GET` reports both together and carries a per-channel `usable` flag — settings present
*and* every credential the declared provider needs SET. That flag is the thing to check after
configuring a tenant, before the feature flag is turned on.

### 4.2 Validation

Two layers, because they need different things.

**Bean validation on the DTOs** (`EmailProviderConfigDTO`, `SmsProviderConfigDTO`) — field shapes.
These run because the settings arrive on a dedicated `@Valid` endpoint rather than as a `JsonNode`
through the generic config API. Both DTOs and every nested block carry
`@JsonIgnoreProperties(ignoreUnknown = false)` **and** a `@JsonAnySetter` that throws: an unknown
property is either a typo that would silently do nothing or an attempt to smuggle a credential
into the settings JSON, and both should be a 400. The annotation is what enforces it, because
Spring Boot's ObjectMapper disables `FAIL_ON_UNKNOWN_PROPERTIES` globally.

**`MessagingProviderSettingsValidator`** — everything cross-field, or that needs system config or
DNS:

- the provider matches its block, and the *other* block is absent
  (`provider: sendgrid` + an `smtp` block is a 400);
- `logoImageUrl`, if present, is an absolute `https` URL;
- the OTP template holds `{otp}` exactly once, `{expiryMinutes}` at most once, and nothing else
  in braces;
- **the three SMTP checks** (below).

### 4.3 The SMTP endpoint rule

A state admin legitimately owns their state's SMTP credentials, but the host those credentials are
sent to is not theirs to choose freely — an unconstrained host would let a settings write make
message-service open a connection to an internal service, or hand the tenant's SMTP password to a
server the writer controls. Four checks stand between, in order of cost:

1. the host is not an IP literal (`HostNames.isIpLiteral`) — a literal would sail past the
   allowlist's name matching;
2. the host is covered by `MESSAGING_PROVIDER_ALLOWED_HOSTS`, which only a super user writes, so
   onboarding a relay is a platform decision;
3. the connection is encrypted — `startTls: true`, or port 465 where TLS is implicit;
4. no address the name resolves to is loopback, private or link-local
   (`SsrfAddressPolicy.isInternalAddress`).

It fails closed throughout: a name that does not resolve is refused, not accepted on the hope it
will resolve safely later.

**Checks 1, 2 and 4 are repeated by message-service immediately before it connects** (check 3 is
re-run by `SmtpMailSenderFactory` instead) — see §5.4.
That is not redundancy. DNS can change between the write and the send, and a super user removing a
host from the allowlist must take effect on the tenants already using it.

`messaging.provider.allow-internal-hosts` (default `false`) skips the address check for local
development against a mail catcher. It logs a WARN naming itself every time it does, and must
match in both services or a host accepted on write is refused at send time.

### 4.4 Envelope encryption

`SecretCryptoService` (one per service — tenant-service wraps, message-service unwraps). Plain
JCE, AES-256-GCM at both levels, 12-byte `SecureRandom` IV, 128-bit tag, stored as
`base64(iv || ciphertext+tag)`:

```
MESSAGING_SECRET_MASTER_KEY_V<n>          (env, base64 32 bytes, never in the DB)
      │  AES-256-GCM,  AAD = "<tenantId>|<keyVersion>|<masterKeyId>"
      ▼
tenant_secret_key.wrapped_key             (the per-tenant data key, DEK)
      │  AES-256-GCM,  AAD = "<tenantId>|<channel>|<secretName>|<keyVersion>"
      ▼
tenant_provider_secret.ciphertext
```

**Why a per-tenant DEK rather than encrypting each secret directly under the master key:**
rotating the master key re-wraps one row per tenant and never touches a secret ciphertext; a state
that believes its credentials are exposed can have only its own DEK rotated; and the master key
never leaves the deployment environment to do bulk work.

**The AAD is what makes a stolen ciphertext useless anywhere but its own row.** Moving a wrapped
key or a secret to another tenant, channel, secret name or key version changes the AAD, so GCM
authentication fails and nothing decrypts. Without it, one tenant's row copied over another's
would decrypt cleanly to the wrong credential.

Three things this has that the existing `PiiEncryptionService` does not: the AAD; key versions at
both levels; and **no plaintext fallback** — `safeDecrypt`'s legacy-row behaviour would be a hole
here, so every failure raises `SecretCryptoException`.

Unwrapped data keys and decrypted plaintext buffers are zeroised in `finally` blocks; the master
keys are zeroised by a shutdown hook.

### 4.5 Concurrency

`getOrCreateActiveKey` and `rotateTenantDataKey` both take
`pg_advisory_xact_lock(namespace, tenantId)` (`TenantProviderSecretRepository.lockKeys`) inside
the `@Transactional` boundary before reading. Without it, two secret writes issued in parallel for
a tenant with no key yet would both see none, both compute `version = 1`, and the second would hit
`uq_tenant_secret_key_active` as a 500.

### 4.6 Key rotation

Two independent operations:

**Master key (KEK) rotation** — `POST /rewrap`. Add `v2` to `messaging.secret.master-keys`, point
`active-master-key-id` at it, call `/rewrap`, then remove `v1` from the environment once no key
still names it. Every configured key id can unwrap; only the active one wraps. A row whose master
key has already left the environment is **recorded in `failedTenantIds` and skipped**, not thrown,
so one stale row cannot stop the rest of the rotation.

**Tenant data key (DEK) rotation** — `POST /tenants/{id}/rotate`. Issues a new `key_version`,
re-encrypts that tenant's stored secrets under it, and retires the previous version. The retired
row is kept so the rotation is auditable. Note this rotates the *encryption*, not the credentials:
those still have to be changed at the provider and re-written.

---

## 5. Read path — `message-service`

### 5.1 Tenant identity on the event

Four events gained optional, additive tenant fields, all `@JsonInclude(NON_NULL)`:

| Event | Producer | Fields added |
| --- | --- | --- |
| `SEND_INVITE_EMAIL` | `UserManagementServiceImpl` | `tenantCode` |
| `SEND_REINVITE_EMAIL` | `UserManagementServiceImpl` | `tenantCode` |
| `SEND_PASSWORD_RESET_EMAIL` | `AuthServiceImpl` | `tenantId`, `tenantCode` |
| `SEND_LOGIN_OTP` | `StaffAuthServiceImpl` | `tenantId`, `tenantCode` |

Super users belong to no tenant (`tenantId 0`), and the producers deliberately send `null` for
them — a super-user invitation is served by the system default.

`TenantRefResolver` normalises whichever half the producer had in scope into one `TenantRef`,
filling the other from `common_schema.tenant_master_table`. Successful lookups are cached forever
in memory (the id ↔ state-code mapping never changes for a live tenant), misses are not, and
**only a pair this class read from the table itself is cached** — an event that already carries
both halves is used as it stands but not remembered, so one producer emitting a mismatched pair
cannot pin `id → wrong code` and have every later half-populated event pick the wrong tenant's
account. A lookup failure is never fatal: the partial reference is returned and the send continues
on the system default.

### 5.2 `TenantChannelProviders` — the one decision point

`emailFor(TenantRef)` and `smsFor(TenantRef)` are the only API. Both are called *per event*, not
held, so a settings change takes effect without a restart; both are cached, so it costs nothing
per message. `AccountEmailService` and `NotificationEventRouter`'s SMS branch are the only
callers, and neither learns that tenants can have providers at all — they still depend only on the
`EmailSender` / `SmsSender` ports.

Resolution order:

1. flag off, or the event carries no tenant id ⇒ **system default**, uncached;
2. cache hit ⇒ whatever was decided last time, including a decision to use the default;
3. no settings row ⇒ **system default**;
4. settings that cannot be built ⇒ ERROR + counter + **system default**;
5. otherwise ⇒ the tenant's own sender.

Step 4 covers: an unknown provider name, a provider with no registered factory, a missing
settings block, a missing or undecryptable credential, a refused SMTP endpoint, an unrenderable
OTP template, and any other `RuntimeException` a factory throws.

**Failing back rather than closed is deliberate.** A state that mistypes its SendGrid key should
have its mail delivered from the platform account with an ERROR in the log, not a silent stop to
its operators' password resets. The mirror of that rule: there is **no** fallback once a sender
exists — an error the provider itself returns takes today's retry and DLT path, because falling
back after the provider has accepted or rejected a message could deliver it twice.

### 5.3 Reading settings leniently

The read-side `EmailProviderSettings` / `SmsProviderSettings` records are the opposite of the
write-side DTOs: `@JsonIgnoreProperties(ignoreUnknown = true)`, and `provider` is held as a **raw
String** rather than the enum.

The row has already been validated once, and the writer may be a newer tenant-service than this
deployment. Binding `provider` as the enum would turn a provider this deployment does not know
into a parse failure for the whole row — indistinguishable from a tenant that configured nothing.
Held as a string, the row still parses, `providerType()` returns `null` via
`fromWireNameOrNull`, and the fallback is taken **with a provider name to log and to count**.

`TenantProviderConfigRepository` answers every failure — malformed row, database error — with
`Optional.empty()` rather than an exception, because a settings read sits on the path of a login
OTP. `TenantProviderSecretRepository` deliberately does the opposite: a missing row is an empty
`Optional`, a database error is an exception, so the two cannot be confused. It is also strictly
**read-only** — every write, rotation and rewrap belongs to tenant-service, so a compromise of the
send path can use a tenant's credentials but cannot change them.

### 5.4 Building the sender

One factory per provider, all registered unconditionally as `@Component`s and collected into an
`EnumMap` by `providerId()` (two factories claiming the same provider is a startup failure):

| Factory | Takes from settings | Takes from the secret store | Takes from system properties |
| --- | --- | --- | --- |
| `SendGridMailSenderFactory` | `fromAddress`, `fromName`, `logoImageUrl`, 5 template ids | `apiKey` | API root |
| `SmtpMailSenderFactory` | `host`, `port`, `username`, `startTls`, `fromAddress` | `password` | subject/body templates |
| `SmsCountrySenderFactory` | `senderId`, 3 DLT ids, `otpTemplate` | `authKey`, `authToken` | base URL |

The split in that table **is** the security boundary: the API base URL and the SMTP subject/body
templates stay system properties, and the credentials come from a location the server derives from
`(tenantId, channel, secretName)`. Nothing a state admin writes can choose where a credential is
sent, and there is no reference field in the settings that could name one.

Every check in a factory is a **build-time** check, so a failure is a `ProviderNotUsableException`
that leaves the tenant on the system default with one ERROR, rather than a per-send failure that
looks like an outage. The factories re-check things tenant-service already enforced on write — the
SMTP TLS rule, the DLT ids, the OTP template, the `authKey` shape — for one reason: a stored row
can predate a rule.

`SmsCountrySender` builds its endpoint as a `URI` through `UriComponentsBuilder.pathSegment(...)`
and passes the `URI` overload, because `authKey` is both the basic-auth username **and** the
`/Accounts/{authKey}/` path segment of every request, and `WebClient.uri(String)` would read it as
a URI template. `SmsCountrySenderFactory` additionally refuses a key outside `[A-Za-z0-9_-]+`
(the dot is excluded, so `..` cannot survive encoding and traverse) and never quotes the rejected
key in the failure message.

`ProviderEndpointPolicy` re-runs the allowlist and address checks for SMTP only. SendGrid and
SMSCountry are not checked: their API hosts are system properties, so there is no caller-chosen
destination to judge.

### 5.5 Caching

Two typed Caffeine caches, one per channel, keyed by `tenantId`:

- `expireAfterWrite` = `notification.per-tenant-providers.cache-ttl`, default **10m**
- `maximumSize` = `...cache-max-size`, default **500**

Building a sender costs two queries and, for SMTP, a DNS resolution — none of which belong on the
path of every OTP. The outcome is cached whichever way it went, **the fallback included**: an
unconfigured tenant is the common case during rollout and must not pay two queries a message.

Caching a build failure means a transient DNS outage pins that tenant to the system default until
the entry expires. That is the intended trade — the alternative is re-running a failing DNS lookup
on the listener thread for every message — and both the TTL and `TENANT_CONFIG_UPDATED` bound it.

**Why Caffeine and not Redis**, although the platform has Redis: the cached values are live
objects (a `WebClient`, a `JavaMailSenderImpl`), not data, so there is nothing to put in Redis;
making Redis useful would mean caching *decrypted credentials*, giving back exactly what §4.4
bought; and it would add a network hop in front of every login OTP. Coherence across replicas —
the real argument for a shared cache — is solved instead by §5.6.

### 5.6 Invalidation

`TenantConfigUpdatedListener` consumes `tenant-service-topic` and calls `evict(tenantId, channel)`
for `TENANT_CONFIG_UPDATED` events naming `EMAIL_PROVIDER_SETTINGS` or `SMS_PROVIDER_SETTINGS`.
A **secret** write raises the same event under its channel's settings key, which is why the secret
store needs no event of its own.

Three deliberate departures from the service's main consumer, served by a dedicated
`tenantEventListenerContainerFactory`:

- **a unique group id per instance** (`message-service-provider-cache-<UUID>`), so *every* replica
  evicts. With the shared group id a config change would reach exactly one replica and the others
  would serve a stale provider until the TTL expired.
- **`auto.offset.reset = latest`**, because a brand-new group id with `earliest` would replay the
  topic's whole retention on the first poll to evict caches that are still empty.
- **no retries and no DLT.** A failed eviction is answered by the TTL; republishing it would
  create a topic nothing reads.

The listener is `@ConditionalOnProperty(... havingValue = "true")`, so with the flag off it is not
registered at all and no pod subscribes to a topic it otherwise ignores. That is also what makes
turning the flag off a real rollback rather than half of one.

Note the cost of the fresh group id: each restart of each replica leaves an abandoned consumer
group on the broker until `offsets.retention.minutes` reaps it. That is the accepted price of
per-replica eviction, and it only applies while the flag is on.

### 5.7 Startup invariant

`PerTenantProviderStartupValidator` refuses to start the service when the flag is on but no master
key is configured. Without the key, every tenant that configured its own provider silently falls
back to the platform's account — mail still goes out, with an ERROR per message and nothing that
looks like a failure. That is the worst outcome available, so the deployment is stopped instead.

It runs in **`@PostConstruct`**, the same point `SingleTenantModeStartupValidator` uses, and this
is load-bearing: `KafkaListenerEndpointRegistry` is a `SmartLifecycle` started inside
`finishRefresh()`, and `ApplicationReadyEvent` is published *after* that — so a check deferred to
that event would let `NotificationEventRouter` drain `common-topic` first, with every message in
that window sending from the platform's account.

---

## 6. Configuration

### `message-service`

```yaml
notification:
  per-tenant-providers:
    enabled:        ${NOTIFICATION_PER_TENANT_PROVIDERS_ENABLED:false}
    cache-ttl:      ${NOTIFICATION_PER_TENANT_PROVIDERS_CACHE_TTL:10m}
    cache-max-size: ${NOTIFICATION_PER_TENANT_PROVIDERS_CACHE_MAX_SIZE:500}

messaging:
  provider:
    allow-internal-hosts: ${MESSAGING_PROVIDER_ALLOW_INTERNAL_HOSTS:false}
  secret:
    active-master-key-id: ${MESSAGING_SECRET_ACTIVE_MASTER_KEY_ID:}
    master-keys:
      v1: ${MESSAGING_SECRET_MASTER_KEY_V1:}
      # v2: ${MESSAGING_SECRET_MASTER_KEY_V2:}   # added during a rotation; both stay readable
```

`notification.mail.provider` and `notification.sms.provider` still select an adapter, but they now
mean **"the system default provider"** rather than "the only provider". Those beans are built in
`SystemDefaultProviders`, which is where the adapters' former `@ConditionalOnProperty` annotations
and constructor fail-fast checks moved when they stopped being `@Component`s.

`notification.mail.from-name` and `logo-image-url` are the platform's own; a tenant that leaves
either unset falls back to them.

### `tenant-service`

The `messaging.*` block is identical. tenant-service **wraps** each tenant's data key and
message-service **unwraps** it, so both must hold the same key and no other service gets it.

### Master key rules

- base64, exactly 32 bytes; checked at startup, along with `active-master-key-id` naming a
  configured id;
- an empty default is a **supported** state — a deployment not using the feature needs no new
  variable, and tenant-service's secret endpoints answer `503` rather than the service refusing to
  start. message-service refuses to start only when the flag is *on*;
- keep them out of the database, the repo, the vault entry holding the DB password, and any backup
  taken with the database — a stolen backup alone must be useless;
- staging and production use **different** keys, so a dump restored into the wrong environment
  fails to decrypt instead of silently using the wrong credentials;
- back them up separately and test the restore: losing a key makes every tenant secret
  unrecoverable and each state admin has to re-enter theirs.

---

## 7. Observability

**Metric** — `notification.provider.resolution`, incremented on every `emailFor` / `smsFor`:

| Tag | Values |
| --- | --- |
| `channel` | `email`, `sms` |
| `provider` | the wire name, `none`, or `unsupported` |
| `outcome` | `tenant`, `system_default`, `fallback` |

`outcome=fallback` is the one to alert on: it means **a tenant configured its own provider and is
silently not using it**. The `provider` tag is bounded rather than passed through — only a
lower-case, short, punctuation-free name is used as-is, and anything else counts as `unsupported`,
so a malformed row cannot grow the metric's cardinality.

**Logs**

- `[Providers] ... could not be built, falling back to the system default: <reason>` at **ERROR**,
  with the stack trace at DEBUG. The message names the reason and the location and never a
  credential.
- `[Providers] Evicted cached provider [tenantId=, channel=]` at INFO.
- Secret writes and deletes log the **actor**, tenant, channel and secret **names** — never
  values, and `toString()` is overridden on every secret-carrying type (`TenantSecrets`,
  `SmsCountryProperties`, `SmsCountrySettings`, `SetMessagingProviderSecretsRequestDTO`,
  `SecretCryptoService`) because a request DTO is rendered by debug logging and by some binding
  failures.

---

## 8. Behaviour table

| Situation | Result | Logged as |
| --- | --- | --- |
| Flag off | System default | `outcome=system_default, provider=none` |
| Event carries no tenant (super user) | System default | `outcome=system_default, provider=none` |
| Tenant has no settings row | System default | `outcome=system_default, provider=none` |
| Settings + secrets complete and valid | Tenant's own sender | `outcome=tenant, provider=<name>` |
| Settings name a provider this build does not know | System default | ERROR, `outcome=fallback, provider=<name>` |
| Settings name a provider but carry no block | System default | ERROR, `outcome=fallback` |
| A required secret is not stored | System default | WARN + ERROR, `outcome=fallback` |
| A secret does not decrypt | System default | ERROR, `outcome=fallback` |
| SMTP host no longer allowlisted, or resolves internally | System default | ERROR, `outcome=fallback` |
| Settings row is malformed JSON | System default | ERROR, `outcome=system_default` |
| Provider returns an error on send | Today's retry → DLT | No fallback, by design |
| Flag on, no master key | **Service refuses to start** | `IllegalStateException` at `@PostConstruct` |
| `MESSAGING_PROVIDER_ALLOWED_HOSTS` unset | No tenant may use SMTP | WARN per resolution |

---

## 9. Duplicated types

There is no shared library module in this repo, so several types exist as near-identical copies in
both services:

| Type | Note |
| --- | --- |
| `MessagingChannel` | Same constants and secret names. |
| `EmailProviderType`, `SmsProviderType` | Same constants, wire names and required secret names. The creators differ on purpose: tenant-service throws `SettingsRejectedException` on an unknown value (a 400); message-service also has `fromWireNameOrNull`, which returns `null` so an unknown provider falls back instead of failing the row. |
| `SecretCryptoService` | tenant-service has the full read/write side; message-service has the unwrap/decrypt half. |
| `SsrfAddressPolicy`, `HostAddressResolver` | Character-identical copies. |
| The allowlist matcher | `MessagingAllowedHostsConfigDTO.allowsSmtpHost` (tenant-service) and `MessagingAllowedHosts.allowsSmtpHost` (message-service) implement the same loop, including skipping a malformed stored entry rather than throwing. A host one service allows and the other refuses would show up as mail that silently falls back. |

The factories' secret-name constants (`SECRET_API_KEY`, `SECRET_AUTH_KEY`, …) are duplicated from
the enums for the same reason, and each factory's test asserts the two agree.

---

## 10. Rollout and rollback

**Rollout**

1. Run V44.
2. Set `MESSAGING_SECRET_ACTIVE_MASTER_KEY_ID` and `MESSAGING_SECRET_MASTER_KEY_V1` on
   **tenant-service and message-service**, to the same value.
3. Super user writes `MESSAGING_PROVIDER_ALLOWED_HOSTS` (needed only if any tenant will use SMTP).
4. State admins configure settings and secrets. Nothing changes yet — with the flag off, a
   configured tenant behaves exactly as an unconfigured one, which is the point of landing the
   write path first: settings can be entered, reviewed and corrected before any message depends
   on them.
5. Verify with `GET /messaging-providers` that each configured channel reads `usable: true`.
6. Set `NOTIFICATION_PER_TENANT_PROVIDERS_ENABLED=true` and restart message-service.
7. Watch `notification.provider.resolution{outcome="fallback"}`.

**Rollback**

- Whole platform: flag to `false`, restart. No code change, no data change.
- One tenant: `DELETE /messaging-providers/{channel}` in tenant-service. Takes effect within
  seconds via `TENANT_CONFIG_UPDATED`, no restart, and the stored secrets are left in place.

---

## 11. Testing

Per `CLAUDE.md`: Mockito for unit tests, Testcontainers for anything touching the database,
WireMock for external HTTP. The branch adds 29 test files and changes 17 more across the three
services. The ones worth knowing about:

| Test | Covers |
| --- | --- |
| `TenantChannelProvidersTest` | The whole resolution table in §8, caching, eviction, metric tags |
| `SecretCryptoServiceTest` (both services) | AAD binding, cross-tenant/channel/name/version rejection, key config validation |
| `TenantProviderSecretRepositoryIntegrationTest` | Upsert/revive, the active-key constraint, the advisory lock |
| `MessagingProviderSettingsValidatorTest` | Provider↔block, allowlist, TLS, DNS, OTP template |
| `SmsCountrySenderTest` / `…FactoryTest` | The `authKey` URI-encoding and shape rules |
| `ProviderEndpointPolicyTest` | The send-time re-check |
| `PerTenantProviderStartupValidatorTest` | Including that the check is `@PostConstruct` and not `@EventListener` |
| `TenantMessagingProviderControllerSecurityTest` | `@RequiresTenantAccess` on every endpoint |

---

## 12. Known limitations

- **WhatsApp is not covered.** Every tenant shares one WhatsApp provider account.
- **`TenantSecrets` values are `String`s and are not zeroised.** Every consumer — a SendGrid
  `Authorization` header, `JavaMailSenderImpl.setPassword`, SMSCountry's basic-auth pair — takes a
  `String`, so a `char[]` would be converted at the first use site and leave an identical copy on
  the heap with the array zeroised for show. The zeroisation that *is* real is on the key material.
  What the type does instead is keep values out of `toString()`, logs and exception messages.
- **`TenantSecretResolver.decrypt` re-queries and re-unwraps the data key once per secret name**,
  so SMSCountry costs two of each. This only happens on a cache miss, not per message.
- **The cache's `removalListener` closes an `AutoCloseable` sender.** Inert today — no adapter
  implements it. Before making one closeable, read the note on `closeIfNeeded`: `emailFor` hands
  the cached instance to the caller, and a TTL expiry or eviction during that window would close
  an instance in use.
