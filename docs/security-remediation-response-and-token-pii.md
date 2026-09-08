# Remediation — CWE-209 / CWE-200: PII and internal state in API responses and JWT claims

**Findings addressed:** two from the third-party audit —

1. **CWE-209** — `POST /api/v1/telemetry/manual-reading` disclosed the configured maximum reading
   and the operator's `lastConfirmedReading` in its response;
   `POST /trigger-welcome-message` returned operator PII with no authentication.
2. **CWE-200** — the Keycloak access token carries the user's name and phone number in
   client-readable claims.

**Status:** the code half is done. Two items are deliberately out of scope for code and are listed
in §4 and §5 — one is Keycloak realm configuration, one is a deployment check.

---

## 1. What was already fixed before this change

Both landed on 2026-09-02 and are in `dev` and `main`; the audit report predates them.

| Commit | Fix |
|--------|-----|
| `7630e211` | Dropped the `Maximum allowed reading: N` suffix from the manual-reading rejection. The threshold now reaches only the anomaly record, the Kafka event and a `log.warn`, all staff-side. Two submissions used to be enough to solve for the tenant's `WATER_NORM` and oversupply percentage. |
| `722caa42` | `GlificWebhookAuthFilter` + `GlificWebhookRoutes`: all 26 Glific webhooks, including `/trigger-welcome-message`, now require `X-Webhook-Token` (SHA-256 compared, `mode: ENFORCE` by default, startup fails if no hash is configured). |

The auditors' exact repro for the threshold leak no longer reproduces. What remained is below.

---

## 2. Code changes in this branch

### 2.1 `lastConfirmedReading` is no longer serialised on the Glific webhook bodies

`CreateReadingResponse.lastConfirmedReading` is now `@JsonIgnore`.

That object is the HTTP body of exactly three routes — `/manual-reading`, `/location` and
`/update-previous-reading` — so every field on it was readable by anyone who could reach them. It is
the other half of the disclosure `7630e211` closed: the rejection stopped naming the ceiling, but
still handed back the operator's current confirmed value.

**Nothing consumes it.** Both checked-in flow definitions (`glific-flows/jalsoochak_dev_flow.json`,
`jalsoochak_uat_flow.json`) were searched: every webhook node reads `@results.<name>.message` and
the HTTP status, and no node references `lastConfirmedReading`.

`@JsonIgnore` rather than deleting the field, because `SingleTenantTelemetryController` and
`MultiFormatReadingController` copy it by getter into `ReadingsDataResponse`. That DTO is served
only on the `X-Api-Key` ingestion routes, where the caller holds the tenant's own key and the value
is their own operator's reading, so the partner contract is unchanged —
`ReadingsDataResponseJsonTest` asserts both halves.

### 2.2 `POST /api/v1/message/trigger-welcome-message` now requires a token

This is a **second** welcome endpoint, in `message-service`, distinct from the telemetry one the
auditors tested. It was `permitAll` and was not covered by `722caa42`.

It was worse than the reported finding:

- `WelcomeMessageTriggerService.resolveTenantCodeByPhone` probes **every** tenant schema until the
  phone matches, so one anonymous request turned a phone number into that person's name, state and
  tenant.
- The response returned `name`, `state`, `phoneNumber`, `contactId` and `tenantCode`.
- The same call runs `glificWhatsAppService.optIn()`, which creates a Glific contact for an unknown
  number and sends it a WhatsApp message. Unauthenticated and unthrottled, that is a spam and cost
  primitive as well as a disclosure.

The `permitAll` line is removed; the route falls through to `anyRequest().authenticated()`.

**Scope of the real exposure:** the api-gateway has never exempted `/message/**` — it falls to
`anyExchange().authenticated()` — so through the gateway this endpoint already required a token. The
`permitAll` only opened it to callers arriving on a non-gateway ingress. Such an ingress demonstrably
exists for telemetry (that is how the auditors reached the telemetry webhooks anonymously); whether
it also fronts message-service is a deployment question, not a repo one. Either way the two layers
now agree on the anonymous surface, which is the same principle `ee14e52f` applied to the
pump-operator routes.

### 2.3 The welcome response no longer carries PII

`TriggerWelcomeMessageResponse` drops `name`, `state` and `phoneNumber`, keeping `success`,
`tenantCode`, `contactId` and `message`. Authentication is the real fix, but a response body
outlives the request in proxy logs and browser history, and none of the three removed fields had a
consumer. `contactId` is the Glific handle support needs to chase a delivery and names nobody; the
caller supplied the phone number, so echoing it back told them nothing they did not have.

### 2.4 `scheme-service` no longer reads the phone number from the token

`SchemeServiceImpl.listSchemesWithYesterdayFinalReading` used to prefer a
`phone_number` / `phoneNumber` / `phone` / `mobile` claim and fall back to the encrypted
`user_table` column only when none was present. Reading the claim is what justified putting the
number in the token at all. The fallback — `findUserPhoneNumberById` + `PiiEncryptionService`
`safeDecrypt` — is now the only path.

This is the code-side prerequisite for §4. The two halves are **order-independent**: removing the
mapper first leaves the code on its existing fallback; landing this first stops reading a claim that
is still present.

---

## 3. What was deliberately not changed

**The operator-facing message text.** Two audit recommendations would degrade the field workflow
without a matching security gain:

- `"Your last confirmed reading was N."` (`BfmReadingService`) — the Glific flow pipes `message`
  straight into WhatsApp, so this string *is* the operator's confirmation cue, and it is their own
  reading being read back to them on a channel they already own.
- `"Dear <name>, You have been registered as Pump Operator for <state>…"`
  (`WelcomeMessageService`) — that is the greeting itself, not an error leak. The fix for it is
  authenticating the caller, which `722caa42` did.

**JWE for the access token.** Every service validates with `oauth2ResourceServer().jwt()` against
Keycloak's JWKS. JWE would mean distributing decryption keys to six services and abandoning standard
resource-server configuration, in order to protect a token whose only holder is the user it
describes. Short token TTL and never logging tokens buy more for far less.

---

## 4. Keycloak realm configuration (not in this repo)

There is no realm export in the repository; the protocol mappers are configured in the Keycloak
admin console, so this half must be done there. `AuthServiceImpl` returns Keycloak's access token
unmodified, so whatever the mappers emit is what clients receive.

| Claim | Source | Action |
|-------|--------|--------|
| `tenant_state_code` | user attribute | **Keep** — tenant isolation in all six services |
| `user_type` | user attribute | **Keep** — role expansion |
| `database_user_id` → `user_id`, `uuid`, `sub` | user attribute / built-in | **Keep** — these are the minimal internal identifiers the audit asks for |
| `phone_number` (and any `phoneNumber`/`phone`/`mobile` variant) | mapper | **Remove** — no longer read after §2.4 |
| `name`, `given_name`, `family_name` | built-in | **Remove** — only `tenant-service/SecurityUtils` reads them, and it already falls back |
| `preferred_username` | built-in (= the user's **phone number**, since `StaffKeycloakService` sets `username` to the phone) | **Remove with care** — see below |

`preferred_username` is the principal name in every service's `JwtAuthConverter`, but each already
falls back to `jwt.getSubject()`, and the value is used only in log lines.
`SchemeServiceImpl.resolveCurrentUserId` also uses it (after `email`) to look the user up, and falls
back to `findUserIdByUuid(jwt.getSubject())`. So removal degrades gracefully — but verify the
dashboard first: if the frontend decodes the token to render the user's name, it needs a `/me` call
instead. The frontend is not in this repository.

**Known residual risk, not fixed here:** the Keycloak *username* is itself the phone number. Even
with the claim removed, the number still appears wherever Keycloak surfaces a username (admin
console, its own logs). Changing that is a login-identifier migration and is being accepted for now.

---

## 5. Deployment checks still open

1. **Is `TELEMETRY_WEBHOOK_AUTH_MODE` actually `ENFORCE` in production?** `AUDIT` and `OFF` both
   serve unauthenticated webhook requests by design — `AUDIT` is the documented kill switch. Check
   the deployed value and the `telemetry.webhook.auth{result="missing"}` counter on
   `/actuator/prometheus`.
2. **Do the production Glific flows send `X-Webhook-Token`?** Neither checked-in flow file does —
   both send only `Content-Type` and `Accept`. `telemetry-service/application.yml` documents the
   required order: header into the flows **first**, then set `token-hashes` and deploy. The reverse
   order rejects every field submission until the flows catch up.
3. **Does any ingress route to `message-service` outside the api-gateway?** That determines whether
   §2.2 closed a live hole or a latent one.

---

## 6. Tests

| Test | Asserts |
|------|---------|
| `ReadingsDataResponseJsonTest` (telemetry) | `lastConfirmedReading` absent from `CreateReadingResponse` JSON, still present on `ReadingsDataResponse`, still readable by getter |
| `WelcomeMessageEndpointSecurityTest` (message) | anonymous `POST /trigger-welcome-message` → 401, `WelcomeMessageTriggerService` never invoked, other routes still authenticated |
| `TriggerWelcomeMessageResponseJsonTest` (message) | no `name` / `phoneNumber` / `state` in the payload; operational fields retained |
| `SchemeYesterdayFinalReadingPhoneSourceTest` (scheme) | the stored, decrypted phone wins over a `phone_number` claim, and the encrypted column is read unconditionally |
