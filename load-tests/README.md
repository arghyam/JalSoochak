# JalSoochak V2 — Load Tests (k6)

Automated load tests for three critical paths:

| Script | Service | Endpoint(s) | Peak VUs |
|--------|---------|-------------|----------|
| `01-analytics-dashboard.js` | analytics-service | 5× GET dashboard endpoints | 500 |
| `02-telemetry-reading-upload.js` | telemetry-service | POST /readings/glific (OCR) + POST /manual-reading | 150 |
| `03-flowvision-direct.js` | FlowVision AI | POST /extract-reading | 80 |

---

## Prerequisites

```bash
# Install k6
brew install k6

# Install Docker (for monitoring stack)
# https://docs.docker.com/get-docker/
```

---

## Step 1 — Populate test data

Before running any test, fill in the three CSV files under `k6/data/`:

| File | What to put in it | How to get the data |
|------|-------------------|---------------------|
| `tenants.csv` | `tenant_id,lgd_id` rows from staging | `SELECT id, lgd_id FROM common_schema.tenant_table WHERE is_active = true` |
| `operators.csv` | Glific `contactId` of test operators | Query `glific_contact_id` from operator rows in any tenant schema |
| `image-urls.csv` | MinIO URLs of real meter images on staging | Upload test photos to staging MinIO; record the URLs |

The CSV files currently contain only comment lines explaining the format. Replace those comments with real data rows.

---

## Step 2 — Start the monitoring stack

```bash
cd load-tests
docker compose -f docker-compose-monitoring.yml up -d
```

Open Grafana at **http://localhost:3000** (user: `admin`, password: `admin`).

To import the official k6 dashboard: Grafana → Dashboards → Import → Dashboard ID **2587**.

---

## Step 3 — Run tests

All scripts accept environment variables for base URLs and date ranges.

### Smoke test (1 VU, verify connectivity)

```bash
cd load-tests

k6 run \
  -e ANALYTICS_BASE_URL=https://jalsoochak.beehyv.com/analytics \
  --vus 1 --duration 30s \
  k6/01-analytics-dashboard.js
```

### Full load test with live Grafana dashboard

```bash
k6 run \
  -e ANALYTICS_BASE_URL=https://jalsoochak.beehyv.com/analytics \
  -e START_DATE=2024-01-01 \
  -e END_DATE=2024-12-31 \
  --out influxdb=http://localhost:8086/k6 \
  k6/01-analytics-dashboard.js
```

```bash
k6 run \
  -e TELEMETRY_BASE_URL=https://jalsoochak.beehyv.com/telemetry \
  --out influxdb=http://localhost:8086/k6 \
  k6/02-telemetry-reading-upload.js
```

```bash
k6 run \
  -e FLOWVISION_BASE_URL=https://jalsoochak.beehyv.com/flowvision \
  --out influxdb=http://localhost:8086/k6 \
  k6/03-flowvision-direct.js
```

### Run against local services

```bash
k6 run \
  -e ANALYTICS_BASE_URL=http://localhost:8087 \
  --vus 10 --duration 1m \
  k6/01-analytics-dashboard.js
```

---

## Required environment variables

The backend services under test require the following environment variables to be configured on the deployment environment (not on the k6 client):

### Message Service & Telemetry Service

| Variable | Purpose | Example | Used by script |
|----------|---------|---------|----------------|
| `GLIFIC_API_URL` | Glific GraphQL endpoint | `https://api.arghyam.glific.com/api` | 02-telemetry (indirect via webhook flow) |
| `GLIFIC_API_KEY` | Glific authentication token | `<token-from-glific-dashboard>` | 02-telemetry (indirect) |
| `GLIFIC_NUDGE_TEMPLATE_ID` | HSM template ID for daily nudge messages | `12345` | — (cron-triggered, not load-tested) |
| `GLIFIC_ESCALATION_TEMPLATE_ID` | HSM template ID for escalation messages with PDF attachment | `12346` | — (cron-triggered, not load-tested) |
| `MINIO_ENDPOINT` | MinIO S3-compatible storage endpoint | `https://minio.staging.jalsoochak.in` | 02-telemetry (OCR image download) |
| `MINIO_ACCESS_KEY` | MinIO access key | `<minio-access-key>` | 02-telemetry |
| `MINIO_SECRET_KEY` | MinIO secret key | `<minio-secret-key>` | 02-telemetry |
| `MINIO_BUCKET` | MinIO bucket name for meter reading images | `jalsoochak-meter-images` | 02-telemetry |
| `MINIO_BASE_URL` | Public-facing base URL for media file access | `https://minio.staging.jalsoochak.in/jalsoochak-meter-images` | 02-telemetry |

**Note:** These variables are **server-side configuration**, not k6 environment variables. They must be set on the staging or production environment where the services run. The k6 scripts only need `ANALYTICS_BASE_URL`, `TELEMETRY_BASE_URL`, and `FLOWVISION_BASE_URL` to point to the correct endpoints.

---

## Thresholds (pass/fail criteria)

| Service | p95 | p99 | Error rate |
|---------|-----|-----|------------|
| Analytics | < 500 ms | < 1 s | < 1% |
| Telemetry (with OCR) | < 12 s | < 15 s | < 2% |
| FlowVision direct | < 3 s | < 5 s | < 5% |

k6 prints a `✓ PASS` / `✗ FAIL` verdict for each threshold at the end of the run. The process exits non-zero on any failure — safe to use in CI.

---

## Per-endpoint metrics

Each script records custom Trend metrics you can chart in Grafana:

| Metric name | What it measures |
|-------------|-----------------|
| `analytics_national_dashboard_ms` | `/national/dashboard` latency |
| `analytics_scheme_regularity_ms` | `/scheme-regularity/average` latency |
| `analytics_water_quantity_periodic_ms` | `/water-quantity/periodic` latency |
| `analytics_submission_status_ms` | `/submission-status` latency |
| `analytics_schemes_dashboard_ms` | `/schemes/dashboard` latency |
| `telemetry_readings_ms` | `/readings/glific` end-to-end (OCR included) |
| `telemetry_manual_reading_ms` | `/manual-reading` (no OCR) |
| `flowvision_extract_ms` | FlowVision `/extract-reading` |
| `telemetry_confirmed_readings` | Count of readings with qualityStatus ≠ REJECTED |
| `telemetry_rejected_readings` | Count of readings with qualityStatus = REJECTED |
| `flowvision_ocr_success` | Count of `result.status == SUCCESS` |
| `flowvision_ocr_failed` | Count of non-SUCCESS OCR responses |

---

## Caution

- **Telemetry `/readings/glific`** writes to the staging database and publishes Kafka events on every call. Run during off-hours or in a dedicated load-test tenant to avoid polluting real operator data.
- **FlowVision** is an external AI service. Spike tests (80 VUs) may exhaust processing quota. Co-ordinate with the FlowVision/Beehyv team before running the spike phase.
- The `qualityStatus: REJECTED` telemetry response is a valid business outcome (low confidence, duplicate image, etc.) — it is **not** counted as a test failure. Only HTTP 5xx responses and non-duplicate 4xx responses are failures.
