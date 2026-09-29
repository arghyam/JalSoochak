# Vendor-Neutral Naming & API Regrouping

**Scope:** two changes. First, vendor names (the WhatsApp provider, the OCR provider, the object
store) and state names were removed from our code, config, logs and living docs, and each remaining
vendor coupling now sits behind a port. Second, every service's controllers were regrouped by
capability instead of by caller.

**Services:** `telemetry-service`, `message-service`, `user-service`, `tenant-service`,
`scheme-service`, `analytics-service`, plus `backend/database/` and the living docs.

**Shipped as:**

| PR | Branch | Contents |
| --- | --- | --- |
| #494 | `vendor-state-neutral-naming-and-refactor` | Telemetry regrouping, vendor and state naming, object-storage port |
| #496 | `api-regrouping` | Scheme, tenant, user and analytics regrouping |
| #498 | `ocr-correlation-id-rename` | OCR correlation column rename (V46) |
| #499 | `legacy-alias-cleanup` | Removal of every backward-compatible alias (V49, V50) |

**Not included:** URL paths, apart from the two changes in §4. DTO JSON field names. Bucket names,
object keys and stored URLs. Kafka topic names. The OCR provider id stored in tenant config.

---

## 1. What it does

| | Before | Now |
| --- | --- | --- |
| Vendor names | In class names, beans, env vars, log tokens and docs | Only in the adapter class that speaks the vendor's protocol |
| State names | In the canonical ingest contract (a state-named request type) | `CanonicalReadingRequest` |
| Outbound vendor calls | Concrete vendor classes injected directly | Ports, with one adapter each |
| Object storage | 3 services call a vendor SDK directly | S3-compatible storage port in every service that stores files |
| Controllers | Grouped by caller or by build order | Grouped by capability, paths unchanged |
| Enforcement | None | CI guard + route-parity and allowlist tests |

**The naming rule.** A vendor name may appear only in the adapter class, its package and its own
test. This follows the `SendGridMailSender` / `SmsCountrySender` pattern already in the repo.
Storage is stricter: its adapter speaks the S3 protocol, which many stores implement, so no class
is named after a storage product at all.

---

## 2. Ports and adapters

```text
 telemetry-service                                   message-service
 ─────────────────                                   ───────────────
 provider/whatsapp/                                  channel/provider/
   WhatsAppContactDirectory  ◀── GlificContactDirectory     WhatsAppSender  ◀── GlificWhatsAppSender
   ConversationResumeGateway ◀── GlificConversationResumeGateway
   InboundMediaFetcher       ◀── GlificMediaFetcher         WhatsAppDeliveryStatusReader ◀── GlificDeliveryStatusReader
 MeterReadingExtractor       ◀── FlowVisionOcrExtractor

 message, telemetry, scheme, user, tenant:  storage/ObjectStorageService  ◀──  storage/S3CompatibleStorageService
```

| Port | Service | Notes |
| --- | --- | --- |
| `WhatsAppContactDirectory`, `ConversationResumeGateway`, `InboundMediaFetcher` | telemetry | The three classes that made real outbound calls. The SSRF guard and bounded download stay on our side of the port. |
| `MeterReadingExtractor` | telemetry | Already existed. Only the vendor-named types around it were renamed (`OcrReadingResult`, `OcrReadingsRetryService`, `OcrResilienceConfig`, …). |
| `WhatsAppSender` | message | 13 methods covering opt-in, flows, HSMs, media upload and report delivery. No per-tenant factory, because all tenants share one WhatsApp account. |
| `WhatsAppDeliveryStatusReader` | message | Delivery-status reads for reconciliation. |
| `ObjectStorageService` | message, telemetry, scheme | Each copy carries only the methods its service uses. |

**Failure classification moved into the adapter.** `WhatsAppChannel` used to classify send
failures by inspecting the vendor's exception types. The adapter now throws
`WhatsAppSendException(stage, errorKey, message)`, so the channel no longer sees vendor types. The
timeout branch still runs first and still stays on our side.

**Storage specifics:**

- `storage.enabled` defaults to `true` in message-, telemetry- and scheme-service, with **no no-op
  fallback bean**. A missing credential stops the context at startup. Without this, report uploads
  would go nowhere without an error.
- `publicUrl(bucket, key)` builds the public URL that Meta downloads from. It joins strings over
  `storage.public-base-url` and makes no network call. The LINK-mode button-prefix check now
  compares against this property.
- The vendor SDK dependency is gone from all four poms that had it.
- tenant-service keeps its older, narrower port (follow-up, §9).

---

## 3. Renames

Class renames do not change the wire format. Every service uses `StringSerializer` /
`StringDeserializer`, so no consumer resolves a payload by class name, and every DTO field has an
explicit `@JsonProperty` or keeps its field name.

### telemetry-service

| Now | Role |
| --- | --- |
| `MeterImageWorkflowService`, `MeterReadingConversationService` | Image and meter-reading workflows |
| `ConversationSelectionService`, `ConversationMessageService`, `ConversationTemplateService`, `ConversationLocalizationService` | Chatbot conversation |
| `OperatorContextService`, `ReadingsAsyncService` | Operator lookup, async webhook replies |
| `WebhookAuthFilter`, `WebhookRoutes`, `@WebhookRoute` | Webhook-token auth and its closed route allowlist |
| `MeterImageWebhookRequest`, `CanonicalReadingRequest`, `OcrReadingResult` | DTOs |
| `whatsAppSyncExecutor` bean, thread prefix `whatsapp-sync-` | Async executor |

**message-service:** `WhatsAppSendResult`, `WhatsAppSendStage`, `ReportSendOutcome`,
`ReportDeliveryMode`, `WhatsAppDeliveryOutcome`, `WhatsAppMessageStatus`,
`WhatsAppDeliveryReconciliationService`.

**Also removed:** the telemetry webhook facade, a pure delegator whose callers now use the workflow
services directly, and telemetry's scaffold `ApiController` with its sample service and DTO.

---

## 4. Contract changes

Each change first shipped with a transition. PR #499 then removed every alias, so the table shows
the final state.

| Item | Now | Transition (removed in #499) |
| --- | --- | --- |
| Meter-image webhook | `POST /api/v1/telemetry/readings/whatsapp` | Old path served as an alias for one release. It now gets **401** plus an `api_key_rejected` WARN, not 404, so a stale caller shows up in the logs. |
| Message-templates config key | `WHATSAPP_MESSAGE_TEMPLATES` | Both spellings were accepted, and the response carried both. V45 copied the rows; V49 retired the old key. |
| Kafka `SEND_LOGIN_OTP` | `whatsapp_contact_id` | Dual-emit and dual-read. An event carrying only the old field now opts in by phone. |
| Kafka `STAFF_SYNC` / `UPDATE_USER_LANGUAGE` | `whatsappLanguageId` | Dual-emit and dual-read. An event carrying only the old field now skips. |
| Welcome flow id | `welcome_flow_id` only | Preference inverted first, fallback then removed; V50 moved the rows. |
| OCR correlation column | `flow_reading_table.ocr_correlation_id` (+ index) | Telemetry read either name until V46 had run everywhere. |
| Env vars | `WHATSAPP_*`, `OCR_*`, `STORAGE_*` | **Hard cutover, no fallback.** |
| Deleted endpoints | `GET /api/v1/telemetry`, `POST /api/v1/publish` | Scaffold code, no callers. |

---

## 5. API regrouping

**Rules every service follows:**

- **URL paths are unchanged.** This is a package and class change only, apart from §4.
- `@PreAuthorize`, `@RequiresTenantAccess` and the single-tenant guards moved **verbatim** with
  each handler. They were never merged into a class-level annotation.
- Path-keyed security allowlists survive a class move. The one class-keyed allowlist (telemetry
  webhooks) was re-keyed on the `@WebhookRoute` marker before anything moved.
- Swagger UI's `@Tag` grouping follows the new classes. operationIds keep their names, except
  user-service's two upload handlers (`uploadPumpOperators`, `uploadUserSchemeMappings`).

| Service | Before → after | Layout |
| --- | --- | --- |
| telemetry | 4 → 7 | `controller/webhook/` (X-Webhook-Token): `Reading`, `Selection`, `IssueReport`, `MeterChange`, `Conversation` `…WebhookController`. `controller/ingest/` (X-Api-Key): `ReadingIngestController`, `MultiFormatReadingController` |
| scheme | 2 → 4 | `SchemeQueryController`, `SchemeStatusController` (status read and write together), `SchemeBulkTransferController`, `PublicSchemeController` (separate base path) |
| tenant | 4 → 8 | `tenant/`: `Lifecycle`, `Config`, `Branding`, `Location`, `ApiToken` · `messaging/`: `TenantMessagingProviderController`, `MessagingSecretController` · `system/`: `SystemConfigController` |
| user | 7 → 7 | `auth/` `AuthController` · `account/` `AccountController` · `admin/` `UserAdminController`, `StaffAdminController` · `operator/` `PumpOperatorQueryController`, `SchemeReadingQueryController` · `bulk/` `BulkUploadController` |
| analytics | 6 → 11 | `scheme/`, `regularity/`, `water/`, `incident/`, `tenant/`, `dashboard/`. Each `/user` variant sits beside its sibling, and `/anomalies` sits with `/anomalies/statuses`. |

**Exception advices (telemetry).** Both advices used to be pinned to a single controller type, so
splitting the controller would have silently dropped the error envelope on most endpoints. Now:

- `WebhookValidationExceptionHandler` binds on `@RestControllerAdvice(annotations = WebhookRoute.class)`.
- `TelemetryValidationExceptionHandler` stays pinned to `ReadingIngestController`. It also publishes
  the `submissionRejected` Kafka event that feeds analytics' reported-scheme KPI.
- `MultiFormatReadingController` is **deliberately outside** that advice, which keeps its own
  response envelope.

**Analytics helpers.** Two private helpers of dissolved classes were lifted into shared beans, so
no copy of either exists:

- `helper/SingleTenantModeGuard` — the national endpoints' single-tenant 403;
- `AuthenticatedRequestContextService.resolveUserIdByUuid`.

The commented-out `/date-dimension` handler was deleted.

---

## 6. Guards

| Guard | Protects |
| --- | --- |
| `.github/workflows/vendor-neutrality-guard.yaml` | Runs `.github/scripts/vendor-neutrality-guard.sh` over the **whole tracked tree** on every PR and on pushes to `main`/`dev`. It has no `paths:` filter, because the per-service image builds never see `docs/`, `gitbook/` or `backend/database/`. Every exemption sits under a named rule (adapter, applied migration, renaming migration, historical record, URL/host, …). |
| `WebhookRouteCoverageTest` | `WebhookRoutes` ⇄ every `@WebhookRoute` handler, in both directions, pinned at 26. A route missing from the allowlist would be **fully public**. |
| `RouteParityTest` (telemetry, scheme, tenant, user, analytics) | The multiset of absolute mapping **values** served. Written before the moves, and not edited by any of them. |
| `SecurityConfigPublicAllowlistTest` (analytics) | The 18-path anonymous-GET allowlist matches real routes, both ways. Nothing covered it before. |
| `EventDtoTest` (user, message) | Each Kafka event's exact JSON field names. |

---

## 7. Configuration and observability

**Env vars and property prefixes** (hard cutover):

| Old (vendor-prefixed) | New | Services |
| --- | --- | --- |
| WhatsApp provider vars (39) | `WHATSAPP_*`, prefix `whatsapp.*` | message, telemetry |
| OCR provider vars (19) | `OCR_*`, prefix `ocr.*` | telemetry |
| Object-store vars | `STORAGE_*`, prefix `storage.*`, plus `STORAGE_ENABLED` (default `true`) | message, telemetry, scheme |

- The report public base URL is `STORAGE_PUBLIC_BASE_URL`. Its value must match the prefix of the
  approved LINK-mode button.
- Missing required variables are **named at startup** rather than resolving silently to `""`.
- Vendor URLs used as YAML default values are unchanged, because they are real endpoints.

**Renamed observability tokens.** Grafana dashboards and Loki recipes live outside this repo.

| Now | Where |
| --- | --- |
| Resilience4j instance `ocrReadings` (`ocrReadings-<providerId>`) | `resilience4j_*{name=…}` metrics |
| Thread prefix `whatsapp-sync-` | `%t` on async telemetry log lines |
| `[WhatsApp]`, `[WhatsAppAuth]`, `[WhatsAppStatus]`, `[Storage]` | Log prefixes |
| `providerMsgId=`, `providerContactId=`, `stage=PROVIDER_ACCEPTED` | Send and reconciliation lines. `providerMsgId` is the join key against provider delivery status. |
| `readings_whatsapp` (webhook), `readings_ocr` (shared OCR path) | Reading log tokens |
| `reading_lenient`, `reading_phone_absent`, `reading_lenient_recorded`, `ocr_result` | Ingest log tokens |

Enum constants and the `telemetry.webhook.auth` counter were already neutral and are unchanged.
Phone numbers are still logged at `DEBUG` only.

---

## 8. Migrations and deploy order

| Migration | Does |
| --- | --- |
| V45 | Copies live message-templates rows to `WHATSAPP_MESSAGE_TEMPLATES`, and leaves the old rows for pods still on old code |
| V46 | Renames the OCR correlation column and index in every tenant schema, and patches `create_tenant_schema()`. Runs outside a transaction, per tenant, under a 3 s lock timeout with retries |
| V49 | Retires the old templates key: renames any sole live old-name row, and soft-deletes the rest |
| V50 | Retires the old welcome-flow key the same way, matching exactly what the old fallback read |

**Deploy order:**

1. #494: update secrets for the env-var cutover **before** the deploy, not with it.
2. #498: deploy telemetry with `TELEMETRY_CACHE_METADATA_ENABLED=false`, then tenant-service (runs
   V46), then restore the cache flag.
3. #499: deploy tenant-service first (runs V49/V50), then telemetry-, user- and message-service in
   any order.

**Rollback:** any service can roll back to the #498 code on its own. V49 and V50 leave every key
under the name the previous code reads first. Do not roll back past V45.

**External hand-offs** (outside this repo): the frontend switches to `WHATSAPP_MESSAGE_TEMPLATES`;
the console flow's webhook node points to `/readings/whatsapp`; ingress serves the new path; ops
env vars; Grafana/Loki recipes.

---

## 9. Known limitations and follow-ups

- **Names that stay by design:**
  - the stored OCR provider id `"flowvision"`, because it is tenant data;
  - the public error codes `FLOW_VISION_FAILED` / `FLOW_VISION_REJECTED`, because renaming them is
    an API contract change;
  - applied migrations, because Flyway checksums them.

  Each sits under its own named rule in the guard.
- **No shared module.** Each service keeps its own copy of the storage port, because the repo has
  no parent pom. A shared module is a follow-up.
- **`MessageTemplateService.findConfigValue` does not filter `deleted_at`**, so a soft-deleted row
  can still win when it is a tenant's newest row. V50 keeps that behaviour on purpose. Fixing it
  needs its own PR and data check.
