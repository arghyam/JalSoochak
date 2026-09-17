# Remediation — CWE-287 on `POST /api/v1/telemetry/readings/reset-latest`

**Finding:** the endpoint accepted requests with no `x-api-key` header and executed the reset,
returning `200` with or without a key. Any caller who knew an operator's phone number could
permanently zero that operator's latest confirmed meter reading.

**Status:** fixed in `telemetry-service`. Callers that do not yet send a key are listed in §5 — the
Glific flow is one of them, and is owned by a separate change.

---

## 1. What was actually wrong

The reported missing check was the visible symptom of three separate defects.

| # | Defect | Effect |
|---|--------|--------|
| 1 | `resetLatestReading` accepted an `X-Api-Key` header and never looked at it | Unauthenticated destructive write (the reported finding) |
| 2 | `resetLatestConfirmedReadingByPhone` resolved the operator by phone **across every tenant schema** | Even with the key added, a tenant-A key would reach a tenant-B operator's reading |
| 3 | No record of the operation | A mass reset would leave the destroyed values unrecoverable and the event undetectable |

Two adjacent routes carried the same defect and are fixed in the same change:

- `PATCH /api/v1/telemetry/schemes/{schemeId}/yesterday-final-reading` — no API key check at all, and
  it selected its tenant from the **unauthenticated** `X-Tenant-Code` header, so an anonymous caller
  could name any tenant and overwrite a submitted reading.
- `PUT /api/v1/telemetry/readings` — authenticated, but its phone-number path used the same unscoped
  cross-tenant operator lookup as defect #2.

### On the audit's first recommendation

> *"…by registering it in the Spring Security filter chain with the API_KEY security requirement."*

`telemetry-service` has no Spring Security on its classpath — there is no filter chain to register
with, and the existing enforcement on `POST`/`PUT /readings` is a hand-written block inside each
controller method. That is the root cause: a per-handler check makes *forgetting it* the default
failure mode, which is exactly what happened when this route was added. Adding a fourth copy of the
block would have fixed this endpoint and left the next one exposed, so authentication moved to a
filter instead. Pulling in `spring-boot-starter-security` for one header check would also have
switched the service's whole request pipeline to deny-by-default mid-incident — a much larger
change, worth doing deliberately rather than as part of this fix.

## 2. Fix

### Authentication — `TelemetryApiKeyAuthFilter` (new)

A fail-closed servlet filter, deny-by-default over the server-to-server prefixes:

- **Protected:** `/api/v1/telemetry/readings` and everything below it; every sub-path of
  `/api/v1/telemetry/schemes/`.
- **Exempt:** an explicit allowlist — `POST /readings/glific` and `POST /schemes` — the two Glific
  webhook routes that fall inside those prefixes. They remain unauthenticated (see §4) and are now
  listed where a reviewer can see them, instead of being exempt by omission.

Consequences worth stating plainly: a new `/readings/**` endpoint is authenticated the moment it is
mapped, and making one public takes a deliberate edit to a named allowlist. The filter normalises the
path the way the dispatcher does — decoding, stripping `;` parameters, collapsing `//`, resolving
`.`/`..` — so `/telemetry/./readings/reset-latest` cannot route to a protected handler while missing
the prefix check.

Handlers still resolve the key themselves as defence in depth; the filter publishes the resolved
tenant on the request, so the key is hashed and looked up once per request in the normal path.

### Authorization — the key's tenant bounds the blast radius

`resetLatestConfirmedReadingByPhone` now requires a tenant id and refuses an operator outside it.
The check is not redundant with passing the tenant into the lookup: `resolveOperatorWithSchema`
*prefers* the given tenant but falls back to a match in any other schema.

A cross-tenant hit and an unknown contact return the **same** 404 with the same reason, so the
endpoint cannot be used to test whether a phone number is registered in another tenant. An unknown
contact now returns 404 rather than the previous 500.

`PATCH …/yesterday-final-reading` takes its schema from the API-key tenant. The `X-Tenant-Code`
header survives only as a fallback for in-process callers with no authenticated tenant — it can no
longer override an authenticated one.

### Audit

Every reset — accepted **and** refused — emits one line:

```
reading_reset api=/api/v1/telemetry/readings/reset-latest status=SUCCESS tenantId=22 \
  phone=****9999 correlationId=<uuid> previousReading=1450 newReading=0 reason="n/a"
```

`previousReading` is the value the reset destroyed; the update overwrites the only copy, so this log
is the record of what was there. Accepted resets log at INFO, refusals at WARN, so a caller sweeping
contactIds appears as a run of `status=REJECTED` lines rather than as silence. Rejections at the
filter log `api_key_rejected` with method, path and remote address. Phone numbers are masked, per the
project's PII rule.

**Suggested alert:** more than N `reading_reset` lines per tenant per hour, or any burst of
`api_key_rejected` against `/readings/reset-latest`.

## 3. Tests

| Test | Covers |
|------|--------|
| `TelemetryApiKeyAuthFilterTest` (10) | Missing/unknown key → 401 and the handler is never invoked; a not-yet-existing `/readings/**` route is protected by default; Glific routes stay reachable; encoded/dot-segment/double-slash paths still match; the rejection body never echoes the submitted key |
| `BfmReadingServiceResetLatestTenantScopeTest` (8) | Cross-tenant reset refused with no write and no event published; refusal indistinguishable from an unknown contact; missing tenant → 401; the destroyed value is returned |
| `SingleTenantTelemetryControllerUnitTest` (updated) | No key / invalid key → 401 **and the reset does not run**; valid key scopes to its tenant; the filter-resolved tenant is trusted without a second lookup; audit line contains the destroyed value and no raw phone |

`resetLatestReadingIsPublicAndDoesNotRequireApiKey` — which pinned the vulnerable behaviour as
intended — is replaced by `resetLatestReadingRejectsARequestWithNoApiKey`.

Full suite: 1055 tests, 0 failures. (`TelemetryTenantRepositoryPumpOperatorIntegrationTest` errors
without a Docker daemon — environmental, unrelated.)

## 4. Not fixed here — still open

- **The 26 Glific webhook routes are unauthenticated at the application layer**, including
  `POST /readings/glific` and `POST /manual-reading`, which write readings. Their only protection is
  network placement. This is a larger change (it needs a webhook authentication scheme and a
  coordinated bot rollout) and is already recorded in
  `security-audit-non-dashboard-apis.md` §1.
- **No rate limiting** on the reading routes. The audit log makes a mass reset detectable; it does
  not make it slower.
- **`security-audit-api-scope.md` listed both `reset-latest` and `yesterday-final-reading` as
  `API-KEY`** when neither enforced one. That doc is a dated audit artifact and is left as filed; as
  of this change the classification is accurate.

## 5. Client impact — who must now send a key

No new *kind* of credential: every affected route uses the same per-tenant `X-Api-Key` header the
Assam integration already sends. `X-Tenant-Code` is no longer consulted on these routes.

| Endpoint | Before | After |
|----------|--------|-------|
| `POST /readings/reset-latest` | header ignored | **`X-Api-Key` required** |
| `PATCH /schemes/{id}/yesterday-final-reading` | no auth; tenant from `X-Tenant-Code` | **`X-Api-Key` required**; tenant from the key |
| `POST` / `PUT /readings` | `X-Api-Key` required | unchanged |
| `POST /readings/formats/{format}` | `X-Api-Key` required | unchanged |

Callers already sending a valid key on the `/readings` routes need no change. The two rows in bold
are the behavioural break.

**Glific/WhatsApp flow — owned separately.** The bot calls `reset-latest` and currently sends no
key, so it will receive 401 until its webhook action carries one. That change is being handled by
another developer and is deliberately **not** part of this commit; the flow exports in
`glific-flows/` are untouched here. Coordinate the two deploys, and note that the production flow
export is not in this repo.

Verification:

```bash
# expect 401
curl -si -X POST "$BASE/api/v1/telemetry/readings/reset-latest" \
  -H 'Content-Type: application/json' -d '{"contactId":"91XXXXXXXXXX"}' | head -1

# expect 200 (disposable test operator only — this destroys their latest reading)
curl -si -X POST "$BASE/api/v1/telemetry/readings/reset-latest" \
  -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"contactId":"91XXXXXXXXXX"}' | head -1
```

## 6. Files changed

```
backend/telemetry-service/src/main/java/org/arghyam/jalsoochak/telemetry/
  config/TelemetryApiKeyAuthFilter.java                  (new — fail-closed gate)
  config/RequestCorrelationFilter.java                   (explicit @Order so it runs first)
  config/OpenApiConfig.java                              (declare the ApiKey scheme)
  controller/SingleTenantTelemetryController.java        (enforce + scope + audit)
  service/BfmReadingService.java                         (tenant-scoped reset and correction)
  service/TelemetrySchemeReadingService.java             (schema from the key, not the header)
```
