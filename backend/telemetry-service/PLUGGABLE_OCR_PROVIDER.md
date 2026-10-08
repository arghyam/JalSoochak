# Pluggable OCR / AI Provider (per tenant)

The external AI service that reads meter values from images is pluggable per state/tenant and per
reading channel. Each provider reads one kind of meter, so it serves one channel. BFM's built-in provider
is `FlowVisionBfmOcrExtractor`, registered under the id `"flowvision"`; ELM's is
`FlowVisionElmOcrExtractor`, registered under `"flowvision-elm"`. A different AI model / endpoint can be
selected for any tenant through configuration, and new providers can be added without touching the
ingestion pipeline.

Only BFM and ELM read photos (`ReadingChannel.supportsImageReading()`). PDU readings are always typed
in. With `ocr.elm.default-provider` set blank, ELM OCR is opt-in per tenant, and an ELM photo from a
tenant that names no ELM provider is rejected like a PDU one.

## Moving parts

| Type | Role |
|------|------|
| `MeterReadingExtractor` | Strategy interface — one bean per provider. `providerId()`, `channel()` + settings-aware `extractReading` / `extractReadingOrThrow`. No bean is `@Primary`; nothing injects a single extractor. |
| `FlowVisionBfmOcrExtractor` | Built-in BFM provider (`providerId = "flowvision"`). Applies the endpoint + auth header from the supplied `OcrProviderSettings`, or its global `ocr.*` config when they are `null`. |
| `FlowVisionElmOcrExtractor` | Built-in ELM provider. Reads the kWh register, on its own `elmOcrRestTemplate` sized for a 20–30 s read. Applies the tenant's `ocr_elm_*` settings, or its global `ocr.elm.*` config for any that are unset. |
| `OcrProviderSettings` | Resolved per-tenant config: provider id, endpoint URL, API key, auth header. |
| `OcrProviderResolver` | Reads a tenant's config keys for the channel → `OcrProviderSettings` (or `null` = use the channel's default provider with its own config). |
| `OcrProviderRegistry` | Indexes all `MeterReadingExtractor` beans by channel and id. `get(channel, providerId)` returns the tenant's provider for that channel, else the channel's default, else empty. An `ocr.elm.default-provider` that isn't registered for ELM fails startup. |
| `ImageReadingCapture` | Picks the provider through the resolver and registry for every photo, and rejects the photo with `IMAGE_NOT_SUPPORTED_FOR_CHANNEL` when the channel doesn't read photos or has no provider. |
| `OcrReadingsRetryService` | Resilience wrapping the provider the registry picked: **per-provider retry + circuit breaker** (isolation), **shared bulkhead** (global concurrency cap). |

Call flow (in `ImageReadingCapture`, called from `BfmReadingService.createReading`):

```
channel doesn't read photos (PDU) ─▶ 400 IMAGE_NOT_SUPPORTED_FOR_CHANNEL   (no config read)

(tenantId, channel) ─▶ OcrProviderResolver.resolve(tenantId, channel)  ─▶ settings or null
                    ─▶ OcrProviderRegistry.get(channel, settings?.providerId)
                         │
                         ├─ empty     ─▶ 400 IMAGE_NOT_SUPPORTED_FOR_CHANNEL   (ELM with no provider)
                         └─ extractor ─▶ OcrReadingsRetryService.extractReading(extractor, imageUrl, settings)
```

A BFM tenant with no `ocr_*` keys gets `null` settings and BFM's default provider
(`ocr.default-provider`, default `"flowvision"`), which reads with its global `ocr.*` config. An ELM
tenant with no `ocr_elm_*` keys gets ELM's default provider (`ocr.elm.default-provider`, default
`"flowvision-elm"`), which reads with its global `ocr.elm.*` config.

## Per-tenant configuration

One set of keys points at one model endpoint, which reads one kind of meter, so each channel that reads
photos has its own set. Add rows to `common_schema.tenant_config_master_table` for the tenant (all keys
optional; setting *any* one of a channel's keys activates the override path for that channel):

| BFM `config_key` | ELM `config_key` | Meaning | Example |
|------------------|------------------|---------|---------|
| `ocr_provider` | `ocr_elm_provider` | Provider id to use | `vision-x` |
| `ocr_url` | `ocr_elm_url` | Endpoint URL | `https://vision-x.example/extract` |
| `ocr_api_key` | `ocr_elm_api_key` | API key/token — a literal, or `env:VAR_NAME` to read from the environment instead of storing the secret in the DB | `env:VISION_X_KEY` |
| `ocr_auth_header` | `ocr_elm_auth_header` | Header carrying the key (default `Authorization` for BFM, `X-API-Key` for ELM) | `X-Api-Key` |

- **BFM:** unspecified keys fall back to the global `ocr.*` defaults.
- **ELM:** unspecified keys never fall back to `ocr.*`, which belong to the BFM model; they fall back
  to ELM's own `ocr.elm.*`, so an ELM photo is never sent to the BFM endpoint or with the BFM key. The
  global `ocr.elm.api-key` is sent only to the global `ocr.elm.url`: a tenant `ocr_elm_url` needs its
  own `ocr_elm_api_key`.
- **PDU** has no keys.

Example (tenant `id = 12` → a separate OCR endpoint with a key from the environment):

```sql
INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value) VALUES
  (12, 'ocr_url',      'https://ocr.tenant-12.example/v1/extract-reading'),
  (12, 'ocr_api_key',  'env:TENANT_12_OCR_API_KEY'),
  (12, 'ocr_auth_header', 'Authorization');
```

BFM's global defaults live under `ocr:` in `application.yml`
(`default-provider`, `url`, `api-key`, `auth-header`). ELM's live under `ocr.elm:`, each with its own
environment variable, and none falls back to BFM's:

| Setting | Environment variable | Default |
|---------|----------------------|---------|
| `ocr.elm.default-provider` | `OCR_ELM_DEFAULT_PROVIDER` | `"flowvision-elm"`; blank = ELM OCR is opt-in per tenant |
| `ocr.elm.url` | `OCR_ELM_URL` | blank; required while the default provider is `"flowvision-elm"` |
| `ocr.elm.api-key` | `OCR_ELM_API_KEY` | blank |
| `ocr.elm.auth-header` | `OCR_ELM_AUTH_HEADER` | `X-API-Key` |
| `ocr.elm.http.connect-timeout-ms` | `OCR_ELM_CONNECT_TIMEOUT_MS` | `5000` |
| `ocr.elm.http.read-timeout-ms` | `OCR_ELM_READ_TIMEOUT_MS` | `120000` |

Every environment sets `OCR_ELM_API_KEY` to a key the ELM service accepts (it checks the `X-API-Key`
header against its `METER_SERVICE_API_KEY`); a missing or wrong key gets every ELM photo a 401
rejection. Every environment also sets `OCR_ELM_URL` to its own ELM endpoint: with `"flowvision-elm"`
as the default provider and no URL, startup fails.

## Adding a new provider

1. Implement `MeterReadingExtractor` (map the vendor's response onto `OcrReadingResult`); annotate `@Service`.
2. Give it a unique `providerId()` (e.g. `"gemini-vision"`) and the `channel()` whose meters it reads.
3. Point a tenant's provider key for that channel (`ocr_provider` or `ocr_elm_provider`) at that id (plus
   the channel's URL / API key keys as needed).

An extractor for a channel that doesn't read photos (PDU) is ignored at startup with a warning. To make
a new provider the one tenants get when they name none, set the channel's default to its id
(`ocr.default-provider` for BFM, `ocr.elm.default-provider` for ELM).

No changes to `BfmReadingService`, the resilience layer, or persistence are required — the registry
discovers the new bean at startup.

## Resilience isolation

Each provider gets its **own retry + circuit breaker**, so a failing/slow AI backend trips only its own
breaker and cannot open the default provider's:

- The instance is keyed on the **resolved** provider (the extractor the registry actually returns), not
  the raw configured id.
- BFM's built-in `"flowvision"` provider (including tenants with no override) uses the tuned
  `ocrReadings` instances configured in `application.yml`. An **unknown/mis-typed `ocr_provider`**
  degrades to that provider in the registry, so it also uses these default instances — it never
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
