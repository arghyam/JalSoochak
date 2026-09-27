# Pluggable OCR / AI Provider (per tenant)

The external AI service that reads water-meter values from images is pluggable per state/tenant.
The built-in provider is `FlowVisionOcrExtractor`, registered under the id `"flowvision"`; a different
AI model / endpoint can be selected for any tenant through configuration, and new providers can be
added without touching the ingestion pipeline.

## Moving parts

| Type | Role |
|------|------|
| `MeterReadingExtractor` | Strategy interface — one bean per provider. `providerId()` + settings-aware `extractReading` / `extractReadingOrThrow`. |
| `FlowVisionOcrExtractor` | Built-in provider (`providerId = "flowvision"`). Applies the endpoint + auth header from the supplied `OcrProviderSettings`. |
| `OcrProviderSettings` | Resolved per-tenant config: provider id, endpoint URL, API key, auth header. |
| `OcrProviderResolver` | Reads a tenant's `ocr_*` config keys → `OcrProviderSettings` (or `null` = use default provider unchanged). |
| `OcrProviderRegistry` | Indexes all `MeterReadingExtractor` beans by id; dispatches by provider id, falling back to the default on an unknown id. |
| `OcrReadingsRetryService` | Resilience wrapping whichever provider is resolved: **per-provider retry + circuit breaker** (isolation), **shared bulkhead** (global concurrency cap). |

Call flow (in `BfmReadingService.createReading`):

```
tenantId ─▶ OcrProviderResolver.resolve(tenantId)
             │
             ├─ null  ─▶ FlowVisionOcrExtractor (global default endpoint)   ← untouched tenants, byte-identical
             └─ settings ─▶ OcrProviderRegistry.get(providerId)
                              .extractReading(imageUrl, settings)
```

## Per-tenant configuration

Add rows to `common_schema.tenant_config_master_table` for the tenant (all keys optional; setting *any*
one activates the override path, unspecified keys fall back to the global `ocr.*` defaults):

| `config_key` | Meaning | Example |
|--------------|---------|---------|
| `ocr_provider` | Provider id to use | `vision-x` |
| `ocr_url` | Endpoint URL | `https://vision-x.example/extract` |
| `ocr_api_key` | API key/token — a literal, or `env:VAR_NAME` to read from the environment instead of storing the secret in the DB | `env:VISION_X_KEY` |
| `ocr_auth_header` | Header carrying the key (default `Authorization`) | `X-Api-Key` |

Example (tenant `id = 12` → a separate OCR endpoint with a key from the environment):

```sql
INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value) VALUES
  (12, 'ocr_url',      'https://ocr.tenant-12.example/v1/extract-reading'),
  (12, 'ocr_api_key',  'env:TENANT_12_OCR_API_KEY'),
  (12, 'ocr_auth_header', 'Authorization');
```

Global defaults live under `ocr:` in `application.yml`
(`default-provider`, `url`, `api-key`, `auth-header`).

## Adding a new provider

1. Implement `MeterReadingExtractor` (map the vendor's response onto `OcrReadingResult`); annotate `@Service`.
2. Give it a unique `providerId()` (e.g. `"gemini-vision"`).
3. Point a tenant's `ocr_provider` config at that id (plus `ocr_url` / `ocr_api_key` as needed).

No changes to `BfmReadingService`, the resilience layer, or persistence are required — the registry
discovers the new bean at startup.

## Resilience isolation

Each provider gets its **own retry + circuit breaker**, so a failing/slow AI backend trips only its own
breaker and cannot open the default provider's:

- The instance is keyed on the **resolved** provider (the extractor the registry actually returns), not
  the raw configured id.
- The built-in `"flowvision"` provider (and the null-settings default path) use the tuned
  `ocrReadings` instances configured in `application.yml`. An **unknown/mis-typed `ocr_provider`**
  degrades to the built-in provider in the registry, so it also uses these default instances — it never
  spawns a phantom `ocrReadings-<typo>` breaker that would never match a real backend.
- Any other registered provider gets instances named `ocrReadings-<providerId>`, **derived from
  the default instance's config** — so tuning (max-attempts, window, thresholds) and the
  transient-exception predicates are inherited identically, while open/closed state and metrics are
  independent. No extra YAML is required to onboard a provider; per-provider metrics are tagged by
  instance name automatically.
- The **bulkhead is shared** (one `ocrReadings` instance): it is a *global* cap on concurrent OCR
  calls protecting the ingestion threads, and is deliberately not split per provider (that would let total
  concurrency grow as the sum across providers).

## Notes

- API keys resolved via `env:` are never persisted in the DB and are logged only in line with the
  project's PII/secret rules (never at INFO+).
