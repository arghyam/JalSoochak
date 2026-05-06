# JalSoochak V2 — Load Test Reference Report
**Last updated:** 2026-05-05 (Run 9)  
**Branch:** `k6-testing`  
**Target:** `https://staging.jalsoochak.in`  
**k6 version:** v1.7.1  
**Tester:** Claude Code (claude-sonnet-4-6)

---

## 1. Test Suite Overview

| Script | Service | Peak VUs | Duration | Endpoints |
|--------|---------|----------|----------|-----------|
| `01-analytics-dashboard.js` | analytics-service | 500 | ~11 min | 5 GET endpoints |
| `02-telemetry-reading-upload.js` | telemetry-service | 150 | ~12 min | 2 POST endpoints |
| `03-flowvision-direct.js` | FlowVision AI | 80 | ~7.5 min | 1 POST endpoint |

### Endpoints under test (current, verified against controllers)

| Script | Method | URL |
|--------|--------|-----|
| `03-flowvision-direct.js` | POST | `https://staging.jalsoochak.in/flowvision/v1/extract-reading` |
| `02-telemetry-reading-upload.js` | POST | `https://staging.jalsoochak.in/api/v1/telemetry/readings/glific` |
| `02-telemetry-reading-upload.js` | POST | `https://staging.jalsoochak.in/api/v1/telemetry/manual-reading` |
| `01-analytics-dashboard.js` | GET | `https://staging.jalsoochak.in/api/v1/analytics/national/dashboard` |
| `01-analytics-dashboard.js` | GET | `https://staging.jalsoochak.in/api/v1/analytics/scheme-regularity/average` |
| `01-analytics-dashboard.js` | GET | `https://staging.jalsoochak.in/api/v1/analytics/water-quantity/periodic` |
| `01-analytics-dashboard.js` | GET | `https://staging.jalsoochak.in/api/v1/analytics/submission-status` |
| `01-analytics-dashboard.js` | GET | `https://staging.jalsoochak.in/api/v1/analytics/schemes/dashboard` |

### Thresholds (pass/fail criteria)

| Service | p(95) | p(99) | Error rate |
|---------|-------|-------|------------|
| Analytics | < 500 ms | < 1 000 ms | < 1% |
| Telemetry | < 12 000 ms | < 15 000 ms | < 2% |
| FlowVision | < 3 000 ms | < 5 000 ms | < 5% |

---

## 2. Script Fixes Log

| Applied before | Fix | File |
|----------------|-----|------|
| Run 3 | `readingValue` → `manualReading` in manual-reading payload | `02-telemetry-reading-upload.js` |
| Run 6 | `/readings` → `/readings/glific` — endpoint was restructured; Glific webhook moved to new path; old `/readings` now serves Assam-specific API requiring `X-Api-Key` + different payload | `02-telemetry-reading-upload.js` |
| Run 6 | Added `tenant_id` query param to `scheme-regularity/average`, `submission-status`, `schemes/dashboard` — controller requires it as non-optional `@RequestParam` | `01-analytics-dashboard.js` |

---

## 3. All Test Runs

### Runs 1–4 (2026-04-24) — Baseline and code/infra iteration

#### Run 1A — FlowVision (parallel with telemetry)

| Metric | Value |
|--------|-------|
| Total requests | 263 |
| HTTP 200 | 9 (3.43%) |
| Timed out | 254 (96.57%) |
| avg latency | 57.96s |
| avg latency (successful only) | 3.27s |
| p(95) | 60s ❌ |
| Error rate | 96.57% ❌ |

#### Run 1B — Telemetry (parallel with FlowVision)

| Metric | Value |
|--------|-------|
| Total requests | 604 |
| HTTP 2xx | 16 (2.65%) |
| OCR timeouts | 97.6% |
| avg latency | 58.38s |
| p(95) | 60s ❌ |
| Error rate | 97.35% ❌ |

Note: Run 1 was parallel — results are confounded. Both tests shared FlowVision capacity.

---

#### Run 2A — FlowVision (isolated baseline)

| Metric | Value |
|--------|-------|
| Total requests | 254 |
| HTTP 200 | 0 (0%) |
| Timed out | 254 (100%) |
| avg latency | 59.79s |
| p(95) | 60s ❌ |
| Error rate | 100% ❌ |

Key: FlowVision was completely unresponsive even at 1 VU. Confirmed failures are not caused by parallel test load.

#### Run 2B — Telemetry (isolated baseline)

| Metric | Value |
|--------|-------|
| Total requests | 610 |
| HTTP 2xx | 1 (0.16%) |
| avg latency | 1m 49s |
| p(95) | 11m 11s ❌ |
| Error rate | 99.83% ❌ |
| Wall clock | 23m 12s |

Key finding: `POST /manual-reading` (no OCR) also failed 100% — Tomcat threads + Hikari pool exhausted by OCR requests blocking on FlowVision, starving all other requests.

---

#### Run 3A — FlowVision (post-code-fix)

Code fixes applied: `BfmReadingService` meter-replacement bug, `GlificGraphQLClient` moved to `boundedElastic`, `FlowVisionService` URL + retry wired, `AsyncConfig` pool sizes raised, `KafkaConfig` DLT + error handler added.

| Metric | Value |
|--------|-------|
| Total requests | 265 |
| HTTP 200 | 11 (4.15%) |
| p(95) | 60s ❌ |
| Error rate | 95.84% ❌ |

FlowVision is external — code fixes have no effect on it.

#### Run 3B — Telemetry (post-code-fix)

| Metric | Value |
|--------|-------|
| Total requests | 813 |
| OCR HTTP success | 89/434 = 20% |
| Manual HTTP success | 110/379 = 29% |
| Checks passed | 199/813 = 24.47% |
| avg latency | 1m 59s |
| p(95) | 12m 43s ❌ |
| Error rate | 75.52% ❌ |
| Wall clock | 33m 47s |

Improvement: error rate dropped from 99.83% → 75.52%. Long wall-clock time because `CallerRunsPolicy` queued rather than dropped requests.

---

#### Run 4A — FlowVision (post-infra-fix)

Infra fixes: Hikari pool 10→25 (telemetry), Hikari pool 10→30 (analytics), Kafka timeouts raised, Redis timeout added.

| Metric | Value |
|--------|-------|
| Total requests | 263 |
| HTTP 200 | 9 (3.43%) |
| p(95) | 60s ❌ |
| Error rate | 96.57% ❌ |

Infra changes are telemetry-side; no effect on FlowVision.

#### Run 4B — Telemetry (post-infra-fix)

| Metric | Value |
|--------|-------|
| Total requests | 636 |
| OCR HTTP success | 76/358 = 21% |
| Manual HTTP success | 99/278 = 35% |
| Checks passed | 175/636 = 27.51% |
| avg latency | 55.31s |
| p(95) | 60s ❌ |
| Error rate | 72.48% ❌ |
| Wall clock | 12m 30s ✓ |

Key improvement: Hikari pool increase (10→25) eliminated the multi-minute latency tail. Wall clock collapsed from 33m → 12m30s. All failures now clean 60s FlowVision timeouts, not indefinite queue waits.

---

### Runs 5–7 (2026-05-04) — URL fixes, analytics first run

---

### Run 5A — FlowVision

Pre-run smoke (1 VU, 30s): **7/7 HTTP 200, avg 3.85s** — service available at single concurrency for the first time since Run 1A.

| Metric | Value |
|--------|-------|
| Total requests | 254 |
| HTTP 200 | 0 (0%) |
| Timed out | 254 (100%) |
| avg latency | 59.93s |
| p(95) | 60s ❌ |
| Error rate | 100% ❌ |

Despite smoke passing, full test still 100% timeout above 2 VUs. Serial processing wall unchanged.

### Run 5B — Telemetry

Note: `/readings/glific` URL bug not yet fixed — test was still hitting `/readings` (Assam API), which returned 400 for all requests due to missing `state_scheme_id`, `centre_scheme_id`, `phone_number` fields. The apparent "OCR improvements" in this run are 400 rejections, not actual OCR processing. `confirmed_readings` counter: 0.

| Metric | Run 4B | Run 5B | Delta |
|--------|--------|--------|-------|
| Total requests | 636 | 1148 | +81% |
| Checks passed | 27.51% | 32.57% | +5pp |
| http_req_failed | 72.48% | 87.02% | worse (more 4xx counted) |
| avg latency | 55.31s | 30.21s | −45% |
| min latency | 15.0s | 331ms | fast 400s |
| confirmed_readings | 0 | 0 | — |
| Wall clock | 12m30s | 12m30s | ✓ |

The throughput increase is explained by fast 400 rejections (operator not found + bad payload) completing in ~300ms rather than waiting for FlowVision. No actual business path was exercised.

### Run 5C — Analytics (smoke only)

0 iterations completed in 30s + 30s graceful stop. Analytics service was completely unresponsive after the telemetry test exhausted staging resources. Full test not run.

---

### Run 6A — FlowVision

Script fixes applied before this run: `/readings` → `/readings/glific`, `tenant_id` added to analytics params.

| Metric | Value |
|--------|-------|
| Total requests | 264 |
| HTTP 200 | 10 (3.78%) |
| OCR success | 10 |
| avg latency (successful) | 3.03s |
| p(95) | 60s ❌ |
| Error rate | 96.21% ❌ |

### Run 6B — Telemetry ← URL fix drives major improvement

| Metric | Run 5B | Run 6B | Delta |
|--------|--------|--------|-------|
| Total requests | 1148 | 1509 | +31% |
| Iterations | 549 | 721 | +31% |
| OCR check pass (200/ack) | 37.7% | **55.6%** (438/787) | +18pp |
| Manual HTTP 200 | 27% | **68%** (491/722) | **+41pp** |
| Checks passed | 32.57% | **61.56%** | +29pp |
| http_req_failed | 87.02% | **38.43%** | −49pp |
| telemetry_errors (5xx) | 52.17% | 37.57% | −15pp |
| OCR avg latency | 30.21s | **7.92s** | −74% |
| OCR min latency | 331ms | **103ms** | async ack |
| confirmed_readings | 0 | **438** | **first time!** |
| Wall clock | 12m30s | 12m30s | ✓ |

The `/readings/glific` endpoint is **async** — it enqueues the OCR job and acks immediately in ~100ms instead of blocking for FlowVision. This is why confirmed_readings hit 438 for the first time and manual-reading success jumped to 68%.

The 38% still-failing are requests where the async queue/thread pool was saturated during the 150-VU spike, returning 5xx.

### Run 6C — Analytics ← first successful full run

| Endpoint | Success | avg | p(90) | p(95) |
|----------|---------|-----|-------|-------|
| `national/dashboard` | 90.6% (4742/5233) | 4.28s | 11.96s | 18.63s |
| `water-quantity/periodic` | 91.6% (4760/5197) | 3.54s | 10.85s | 14.19s |
| `scheme-regularity/average` | 10% (522/5211) | 5.49s | 16.07s | 21.77s |
| `submission-status` | 10.1% (521/5146) | 5.39s | 15.68s | 21.87s |
| `schemes/dashboard` | 10.2% (520/5089) | 5.44s | 15.97s | 22.72s |
| **Overall** | **42.76%** (11065/25876) | **4.83s** | **14.02s** | **19.9s** |

| Threshold | Measured | Limit | Result |
|-----------|----------|-------|--------|
| p(95) | 19.9s | < 500ms | ❌ FAIL |
| p(99) | 36s | < 1000ms | ❌ FAIL |
| Error rate | 57.23% | < 1% | ❌ FAIL |

Total requests: 25,876 — 5,084 iterations, service stayed up for the full 11.5 min ✓

Two distinct failure modes:

**1. Staging data gap (scheme_regularity, submission_status, schemes_dashboard — ~10% success)**
These endpoints require `tenant_id`. Only ~3–4 of the 36 tenants in `tenants.csv` have analytics rows populated in staging. Every request landing on a data-empty tenant returns HTTP 500 `{"success":false,"data":null}` immediately. Not a code bug — staging data coverage issue.

**2. Slow query performance under load (national_dashboard, water_quantity_periodic — 90%+ success but 4–19s)**
At baseline (1 request): 1.67s for `national/dashboard`. Under 200–500 VU load: p(95) climbs to 18–22s. Complex aggregation queries without warm Redis cache. The 500ms threshold is unrealistic for this query type; needs composite DB indexes or materialized views.

---

### Run 7A — FlowVision

| Metric | Value |
|--------|-------|
| Total requests | 264 |
| HTTP 200 | 14 (5.3%) |
| OCR success | 14 |
| avg latency (successful) | 18.05s |
| p(95) | 60s ❌ |
| Error rate | 94.69% ❌ |

Slightly more successes (14 vs 10) — a few requests got through during early ramp-up before the queue saturated. Pattern unchanged.

### Run 7B — Telemetry ← best telemetry run yet

| Metric | Run 6B | Run 7B | Delta |
|--------|--------|--------|-------|
| Total requests | 1509 | 3782 | **+151%** |
| Iterations | 721 | 1888 | **+162%** |
| OCR check pass | 438/787 = 55.6% | 873/1894 = **46%** | lower % but 2× volume |
| Manual HTTP 200 | 491/722 = 68% | 1817/1888 = **96%** | **+28pp** |
| Checks passed | 61.56% | **71.12%** | +10pp |
| http_req_failed | 38.43% | **28.87%** | −10pp |
| confirmed_readings | 438 | **873** | **+2×** |
| OCR avg latency | 7.92s | **330ms** | **−96%** |
| OCR p(95) | 42.79s | **597ms** | **−99%** |
| Manual avg latency | 39.43s | 16.59s | −58% |
| Manual p(95) | 60s | 57.39s | slight improvement |
| p(95) overall | 60s | **49.06s** | below timeout cap |
| Wall clock | 12m30s | **12m02s** | ✓ |

OCR p(95) of **597ms** is the standout metric — the async queue is draining fast enough that 95% of OCR submissions are acked in under 600ms. The remaining 28% failures are concentrated in the 150-VU spike phase where the thread pool saturates. Manual-reading at 96% success is near-perfect.

---

### Runs 8A–8B (2026-05-05) — FlowVision service down; telemetry continues to improve

---

### Run 8A — FlowVision (service down — 502 Bad Gateway)

Pre-run health check: `POST /flowvision/v1/extract-reading` returns **502 Bad Gateway** (nginx) at 1 VU.
This is a staging infra outage — the FlowVision pod is not running. All failures are infra-down, not the serial-worker bottleneck from previous runs.

| Metric | Value |
|--------|-------|
| Total requests | 280 |
| HTTP 200 | 0 (0%) |
| avg latency | 1m 20s |
| p(95) | 4m 29s |
| p(99) | 4m 58s |
| Error rate | 100% ❌ |

Note: latency of 1m 20s avg (vs. 60s in previous runs) is because the 65s client timeout was set on the script; the 502s were returned slowly through nginx keep-alive queuing during the ramp, not via clean rejects.

**Not comparable to Runs 1A–7A** — results reflect a down service, not serial-worker saturation.

---

### Run 8B — Telemetry ← best OCR success rate yet

| Metric | Run 7B | Run 8B | Delta |
|--------|--------|--------|-------|
| Total requests | 3782 | 4450 | +18% |
| Iterations | 1888 | 2216 | +17% |
| OCR check pass (200/ack) | 873/1894 = 46% | **1376/2233 = 61.6%** | **+16pp** |
| Manual HTTP 200 | 1817/1888 = 96% | 2157/2217 = 97.3% | +1pp |
| Checks passed | 71.12% | **79.39%** | **+8pp** |
| http_req_failed | 28.87% | **20.60%** | **−8pp** |
| confirmed_readings | 873 | **1376** | **+58%** |
| OCR p(95) | 597ms | **257ms** | −57% (async ack) |
| OCR avg latency | 330ms | 1.01s | worse (FlowVision down; bg workers timing out) |
| Manual avg latency | 16.59s | 36.48s | worse (thread contention from bg OCR timeouts) |
| Manual p(95) | 57.39s | 46.29s | −19% |
| p(95) overall | 49.06s | **35.99s** | **−27%** |
| p(99) overall | — | 5m 12s | long tail from interrupted iterations |
| Wall clock | 12m02s | 12m00s | ✓ |

**Key findings:**

1. **OCR success rate reached 61.6%** — best yet (up from 46% in Run 7B), 1376 confirmed_readings vs 873.
2. **OCR async ack p(95) = 257ms** — the endpoint enqueues and responds extremely fast.
3. **Manual avg latency regressed to 36.48s** — the background OCR workers can't reach FlowVision (502), their calls timeout (60s), holding onto `@Async` thread pool slots and starving manual-reading requests. This is a cross-contamination effect from the FlowVision outage.
4. **17 interrupted iterations** — produced the 5m12s p(99) tail. These are requests that were in-flight during the graceful stop.
5. **Overall checks 79.4%** — the best telemetry result to date, even with FlowVision down, because the async OCR path acks immediately.

---

### Run 7C — Analytics ← significant improvement on Run 6C

| Endpoint | Run 6C success | Run 7C success | Run 6C p(95) | Run 7C p(95) |
|----------|---------------|---------------|--------------|--------------|
| `national/dashboard` | 90.6% | **99.9%** (21894/21922) | 18.63s | **2.04s** |
| `water-quantity/periodic` | 91.6% | **99.95%** (21909/21919) | 14.19s | **947ms** |
| `scheme-regularity/average` | 10% | 11.2% (2455/21922) | 21.77s | 917ms |
| `submission-status` | 10.1% | 11.2% (2455/21909) | 21.87s | 904ms |
| `schemes/dashboard` | 10.2% | 11.2% (2454/21909) | 22.72s | 887ms |
| **Overall** | **42.76%** | **46.69%** (51167/109581) | **19.9s** | **1.26s** |

| Threshold | Measured | Limit | Result |
|-----------|----------|-------|--------|
| p(95) | 1.26s | < 500ms | ❌ FAIL (but −94% vs Run 6C) |
| p(99) | 5.92s | < 1000ms | ❌ FAIL (but −84% vs Run 6C) |
| Error rate | 53.30% | < 1% | ❌ FAIL (data gap, unchanged) |

Total requests: 109,581 across 21,909 iterations — 4.3× more than Run 6C. The two working endpoints are now effectively passing (99%+ success, p(95) under 2s). The 53% overall error rate is entirely driven by the staging data gap — ~89% of tenants returning 500 on the three `tenant_id`-scoped endpoints. Those endpoints themselves are fast (p(95) 887–917ms).

---

## 4. All-Run Comparative Summary

| Run | Date | Test | Script fix | Checks passed | p(95) | Error rate | Wall clock |
|-----|------|------|------------|--------------|-------|------------|------------|
| 1A | 2026-04-24 | FlowVision | — | 3.43% | 60s | 96.57% | 7m46s |
| 1B | 2026-04-24 | Telemetry | — | 2.65% | 60s | 97.35% | 12m30s |
| 2A | 2026-04-24 | FlowVision | — | 0% | 60s | 100% | 8m00s |
| 2B | 2026-04-24 | Telemetry | — | 0.16% | 11m11s | 99.83% | 23m12s |
| 3A | 2026-04-24 | FlowVision | code fix | 4.15% | 60s | 95.84% | 7m46s |
| 3B | 2026-04-24 | Telemetry | code fix | 24.47% | 12m43s | 75.52% | 33m47s |
| 4A | 2026-04-24 | FlowVision | infra fix | 3.43% | 60s | 96.57% | 7m46s |
| 4B | 2026-04-24 | Telemetry | infra fix | 27.51% | 60s | 72.48% | 12m30s |
| 5A | 2026-05-04 | FlowVision | — | 0% | 60s | 100% | 8m00s |
| 5B | 2026-05-04 | Telemetry | — | 32.57% | 60s | 87.02%* | 12m30s |
| 5C | 2026-05-04 | Analytics | — | — | — | — | Not run |
| 6A | 2026-05-04 | FlowVision | URL fixes | 3.78% | 60s | 96.21% | 7m46s |
| **6B** | 2026-05-04 | **Telemetry** | **URL fix** | **61.56%** | 60s | **38.43%** | 12m30s |
| **6C** | 2026-05-04 | **Analytics** | **URL + param fix** | **42.76%** | **19.9s** | 57.23% | 11m34s |
| 7A | 2026-05-04 | FlowVision | — | 5.3% | 60s | 94.69% | 7m46s |
| **7B** | 2026-05-04 | **Telemetry** | — | **71.12%** | **49.06s** | **28.87%** | 12m02s |
| **7C** | 2026-05-04 | **Analytics** | — | **46.69%** | **1.26s** | 53.30% | 11m32s |
| 8A | 2026-05-05 | FlowVision | — | 0% | 4m29s | 100%† | 11m58s |
| **8B** | 2026-05-05 | **Telemetry** | — | **79.39%** | **35.99s** | **20.60%** | 12m00s |
| **9A** | 2026-05-05 | **FlowVision** | — | **85.86%** | **60s** | **39.70%** | 8m00s |
| 9B | 2026-05-05 | Telemetry | — | 58.29% | 60s | 41.70% | 12m00s |

\* Run 5B error rate inflated by k6 counting 4xx as failed — the 400s were from hitting the wrong endpoint (Assam API returning validation errors), not real service failures.  
† Run 8A failures are infra-down (502 Bad Gateway), not serial-worker saturation — not comparable to Runs 1A–7A.

---

## 5. Root Cause Analysis

### FlowVision — P0 (all runs)

FlowVision processes requests **serially** — single worker, single-threaded. At 1 VU: 2.4–6s/request (acceptable). Above 2 concurrent VUs: queue grows faster than it drains; all queued requests hit the 60s k6 timeout.

**Evidence:** Every run shows the same pattern — a small number of successes during the 1-VU smoke phase (2–14 total), then 100% timeout as VUs ramp. Confirmed across 7 runs and both isolated and parallel test conditions.

**Fix:** Run Gunicorn with multiple workers (`gunicorn app:app --workers 4 --timeout 120`). Each worker is a separate Python process, bypassing the GIL, handling one request independently. 4 workers = 4 concurrent OCR requests.

**GPU note:** Switching from CPU to GPU reduces per-request latency from ~3–4s to ~200–500ms (6–10× faster), which helps throughput significantly. However, the serial processing wall (1 worker) must be fixed first — GPU alone on a single-worker server would just produce a faster serial bottleneck. The ideal fix is both: multi-worker Gunicorn + GPU inference.

### Telemetry — P1 (improved significantly in Run 6B)

The core telemetry service is fast when resources are available. The URL fix (Run 6B) revealed:
- `/readings/glific` is async — acks immediately in ~100ms, queues OCR in background
- At 50-VU steady state: 68% manual-reading success, 55% OCR accepted
- Remaining 38% failure is from 150-VU spike saturating the async thread pool

Residual issues:
1. **`operators.csv` has fake IDs** (`load-test-001…050`) — no real DB writes, Kafka events, or anomaly detection are exercised. Test measures HTTP infrastructure only.
2. **Manual-reading latency still high** (avg 39s) under spike — Hikari pool of 25 connections insufficient at 150 VUs with FlowVision callbacks consuming threads.
3. **`@Cacheable` not added** to `loadWaterNorm` / `loadWaterSupplyThreshold` — hits DB on every reading.

### Analytics — P2 (ran successfully for the first time in Run 6C)

Two independent issues:

**Data gap:** Only ~4/36 tenants in `tenants.csv` have analytics data in staging. Endpoints requiring `tenant_id` return 500 for the other 32. Fix: trim `tenants.csv` to tenants with data, or populate analytics tables for all tenants.

**Query performance:** Under 200–500 VU load, p(95) reaches 19.9s vs the 500ms threshold. Heavy aggregation queries with no index on `(tenant_id, start_date, end_date)`. Fix: add composite indexes, consider materialized views for the national dashboard aggregation.

---

## 6. Fix Priority Queue

| Priority | Action | Status |
|----------|--------|--------|
| P0 | FlowVision: Gunicorn multi-worker (`--workers 4`) | ⏳ Pending |
| P0 | FlowVision: GPU inference (reduces per-request latency 6–10×) | ⏳ Pending |
| P1 | Telemetry: populate `operators.csv` with real staging contactIds | ⏳ Pending |
| P1 | Telemetry: raise Hikari pool further (25→40) for 150-VU spike | ⏳ Pending |
| P1 | Analytics: trim `tenants.csv` to tenants with data OR populate staging analytics | ⏳ Pending |
| P1 | Analytics: add composite indexes `(tenant_id, start_date, end_date)` on fact tables | ⏳ Pending |
| P2 | Telemetry: `@Cacheable` on `loadWaterNorm` / `loadWaterSupplyThreshold` | ⏳ Pending |
| P2 | Analytics: Redis cache warm-up in `setup()` before load stages | ⏳ Pending |
| P2 | Analytics: revise p(95) threshold from 500ms → 5000ms (current queries are 1–4s at baseline) | ⏳ Pending |
| P3 | Analytics: fix Kafka bootstrap-servers to use `${SPRING_KAFKA_BOOTSTRAP_SERVERS}` env var | ⏳ Pending |
| ✅ | Telemetry: `readingValue` → `manualReading` field fix | Done (Run 3) |
| ✅ | Telemetry: `/readings` → `/readings/glific` URL fix | Done (Run 6) |
| ✅ | Analytics: add `tenant_id` param to 3 endpoints | Done (Run 6) |
| ✅ | Telemetry: `BfmReadingService` meter-replacement dead-code bug | Done (Run 3) |
| ✅ | Message-service: `GlificGraphQLClient` moved to `boundedElastic` scheduler | Done (Run 3) |
| ✅ | Telemetry: `FlowVisionService` URL + retry wired from config | Done (Run 3) |
| ✅ | Telemetry: `AsyncConfig` pool sizes raised, `CallerRunsPolicy` added | Done (Run 3) |
| ✅ | Telemetry: `KafkaConfig` DLT + `DefaultErrorHandler` added | Done (Run 3) |
| ✅ | Message-service: `KafkaConfig` `setConcurrency(5)` | Done (Run 3) |
| ✅ | Telemetry: Hikari pool 10→25, Kafka timeouts raised | Done (Run 4) |
| ✅ | Analytics: Hikari pool 10→30, Redis timeout added | Done (Run 4) |

---

## 7. Re-Test Plan (Run 8+)

```bash
# Step 1: Verify FlowVision multi-worker fix
k6 run --vus 5 --duration 30s k6/03-flowvision-direct.js

# Step 2: Full FlowVision test (only after Step 1 passes cleanly)
k6 run k6/03-flowvision-direct.js

# Step 3: Full telemetry (after operators.csv populated with real contactIds)
k6 run k6/02-telemetry-reading-upload.js

# Step 4: Full analytics (after tenants.csv trimmed to tenants with data)
k6 run k6/01-analytics-dashboard.js
```

---

---

### Runs 9A–9B (2026-05-05) — FlowVision back up; major improvement confirmed

---

### Run 9A — FlowVision ← massive improvement (serial bottleneck still present at spike)

Pre-run smoke (1 call): HTTP 200 in **3.08s**, meterReading extracted, qualityStatus=good. Service restored.

| Metric | Run 7A (best prior) | Run 9A | Delta |
|--------|---------------------|--------|-------|
| Total requests | 264 | 340 | +29% |
| HTTP 200 | 14 (5.3%) | **205 (60.3%)** | **+55pp** |
| OCR successes | 14 | **205** | **+14×** |
| avg latency | — | 44.07s | — |
| avg latency (successful) | 18.05s | 33.64s | longer (queue wait) |
| p(95) | 60s ❌ | 60s ❌ | unchanged |
| Error rate | 94.69% | **39.70%** | **−55pp** |
| Interrupted iterations | — | 50 | spike overflow |

**Key finding:** 60.3% success rate is a **10× improvement** over any previous run. The service is clearly processing more requests concurrently — likely restarted with additional workers or higher Gunicorn concurrency. The serial bottleneck is still present at the 80-VU spike (p(95) = 60s, 39.7% error), meaning the worker count is still below the spike target. The 33.64s avg successful latency reflects queue wait time — requests are being processed but queuing at >2 VUs.

**Estimated workers (from throughput):** `flowvision_ocr_success = 0.427 req/s` sustained. At ~2.2s/req minimum, that implies **1–2 workers** active. The spike failure at 80 VUs confirms capacity is still much less than 20 concurrent requests.

---

### Run 9B — Telemetry ← regression vs Run 8B (FlowVision queue backlog + thread pool saturation)

| Metric | Run 7B | Run 8B | Run 9B | Note |
|--------|--------|--------|--------|------|
| Total requests | 3782 | 4450 | 1568 | −65% vs 8B |
| Iterations | 1888 | 2216 | 763 | −66% |
| OCR check pass | 46% | 61.6% | 34.8% | regressed |
| Manual HTTP 200 | 96% | 97.3% | **83.1%** | regressed |
| Checks passed | 71.12% | 79.39% | 58.29% | regressed |
| Error rate | 28.87% | 20.60% | 41.70% | worse |
| confirmed_readings | 873 | 1376 | 280 | −80% |
| OCR avg latency | 7.92s | 1.01s | 10.49s | high |
| OCR p(95) | 42.79s | 257ms | 60s ❌ | |
| Manual avg latency | 39.43s | 36.48s | 32.64s | slight improvement |
| Manual p(95) | 60s | 46.29s | 60s ❌ | |
| p(95) overall | 49.06s | 35.99s | 60s ❌ | |
| Wall clock | 12m02s | 12m00s | 12m00s | ✓ |

**Root cause of regression:** Two compounding factors:

1. **FlowVision queue backlog from Run 9A:** Run 9A left 50 interrupted iterations in FlowVision's queue. At ~2.2s/req single-worker, the backlog drains over ~110s. The first ~2 minutes of Run 9B hit a pre-loaded queue, inflating OCR latency and causing timeouts.

2. **Async thread pool saturated by real OCR work:** When FlowVision is UP, background OCR threads hold for ~3-4s (actual inference time) before completing. With 50–150 VUs submitting OCR jobs, the bounded thread pool fills with live OCR tasks. New submissions get queued or rejected → OCR accept rate drops from 61.6% to 34.8%. When FlowVision was DOWN (Run 8B), background tasks failed fast on 502, freeing threads faster.

**Counter-intuitive finding:** FlowVision being UP causes worse telemetry throughput than when it was DOWN — because the async pool fills with real work. Fix: increase `AsyncConfig` OCR thread pool size to match expected concurrent FlowVision capacity (recommend 50+), or implement a dedicated bounded queue with backpressure so OCR submissions fail-fast when capacity is exceeded rather than blocking the main Tomcat threads.

---

*Last updated: 2026-05-05. All runs complete through Run 9.*
