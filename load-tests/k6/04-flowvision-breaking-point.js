/**
 * Load test: FlowVision breaking-point ramp
 *
 * Purpose : Find the exact concurrency level where FlowVision's latency and
 *           error rate begin to degrade. Run this after changing WORKERS or
 *           switching GPU on/off to determine the new safe operating range.
 *
 * Method  : Step-wise ramp — 60 s steady hold at each VU level so latency
 *           stabilises before the next step. No sleep between requests so
 *           VU count maps directly to in-flight request count.
 *           Steps: 1 → 2 → 4 → 6 → 8 → 10 → 15 → 20
 *
 * What to watch:
 *   • p(95) latency per step — should be flat up to WORKERS count, then climb
 *   • error rate (http_req_failed) — spikes when Gunicorn queue fills / timeouts
 *   • flowvision_ocr_success vs flowvision_ocr_failed counters
 *
 * Expected result with WORKERS=4, CPU-only (~3.5 s/req):
 *   • 1–4 VUs : p(95) ≈ 3–5 s, error ≈ 0 %   ← safe zone
 *   • 5–8 VUs : p(95) climbing to 10–20 s, occasional timeouts
 *   • 9+ VUs  : queue depth grows unboundedly, mass timeouts
 *
 * Expected result with GPU (~0.4 s/req):
 *   • 1–20 VUs: p(95) ≈ 0.5–2 s, error ≈ 0 %  (breaking point > 20)
 *
 * Environment variables:
 *   FLOWVISION_BASE_URL  Base URL (default: https://staging.jalsoochak.in/flowvision)
 *
 * Data file:
 *   load-tests/k6/data/image-urls.csv — column: imageUrl
 */

import http from 'k6/http';
import { check, group } from 'k6';
import { SharedArray } from 'k6/data';
import { BASE_URLS } from './lib/config.js';
import { Trend, Rate, Counter } from 'k6/metrics';

// ── Custom metrics ─────────────────────────────────────────────────────────────
const extractDuration = new Trend('flowvision_bp_duration', true); // true = display in ms
const errorRate       = new Rate('flowvision_bp_errors');
const ocrSuccess      = new Counter('flowvision_bp_ocr_success');
const ocrFailed       = new Counter('flowvision_bp_ocr_failed');

// ── Test data ─────────────────────────────────────────────────────────────────
const imageUrls = new SharedArray('imageUrls', function () {
  return open('./data/image-urls.csv')
    .split('\n')
    .slice(1)
    .filter((line) => line.trim())
    .map((line) => line.trim());
});

// ── Step-ramp stages ──────────────────────────────────────────────────────────
// Each step: 10 s ramp + 60 s hold = 70 s per step.
// Total duration ≈ 10 min. Abort the run manually if error rate hits 50 %+.
export const options = {
  stages: [
    // ── Step 1: 1 VU — baseline (should always pass) ──
    { duration: '10s', target: 1  },
    { duration: '60s', target: 1  },

    // ── Step 2: 2 VUs ──
    { duration: '10s', target: 2  },
    { duration: '60s', target: 2  },

    // ── Step 3: 4 VUs — matches WORKERS=4 (expected clean) ──
    { duration: '10s', target: 4  },
    { duration: '60s', target: 4  },

    // ── Step 4: 6 VUs — 2 over worker count, queue starts forming ──
    { duration: '10s', target: 6  },
    { duration: '60s', target: 6  },

    // ── Step 5: 8 VUs ──
    { duration: '10s', target: 8  },
    { duration: '60s', target: 8  },

    // ── Step 6: 10 VUs ──
    { duration: '10s', target: 10 },
    { duration: '60s', target: 10 },

    // ── Step 7: 15 VUs ──
    { duration: '10s', target: 15 },
    { duration: '60s', target: 15 },

    // ── Step 8: 20 VUs — the claimed target ──
    { duration: '10s', target: 20 },
    { duration: '60s', target: 20 },

    // ── Ramp down ──
    { duration: '30s', target: 0  },
  ],

  thresholds: {
    // No hard pass/fail — the point is to *observe* where latency climbs.
    // With WORKERS=4 and CPU inference at ~3.5 s/req:
    //   queue_wait ≈ (VUs - WORKERS) / (WORKERS / inference_time)
    //   At 20 VUs: wait ≈ 14 s → p(95) ≈ 17 s — slow but not erroring.
    // Errors only occur when total time exceeds the k6 client timeout (65 s).
    'flowvision_bp_duration': ['p(95)<65000'],  // 65 s — matches client timeout
    'flowvision_bp_errors':   ['rate<1.0'],     // observe only; don't abort early
  },
};

const HEADERS    = { 'Content-Type': 'application/json' };
const REQ_PARAMS = {
  headers: HEADERS,
  tags:    { endpoint: 'flowvision_extract_reading' },
  // 65 s timeout — longer than one full inference so queued requests are not
  // killed client-side before the server can respond
  timeout: '65s',
};

// ── Default function (one VU iteration) ──────────────────────────────────────
export default function () {
  const imageUrl = imageUrls[Math.floor(Math.random() * imageUrls.length)];

  group('flowvision_extract_reading', function () {
    const payload = JSON.stringify({ imageURL: imageUrl });
    const res     = http.post(`${BASE_URLS.flowvision}/v1/extract-reading`, payload, REQ_PARAMS);

    const httpOk = check(res, {
      'status 200': (r) => r.status === 200,
    });

    extractDuration.add(res.timings.duration);
    errorRate.add(!httpOk);

    if (httpOk) {
      try {
        const body   = res.json();
        const status = body?.result?.status;
        if (status === 'SUCCESS') {
          ocrSuccess.add(1);
        } else {
          ocrFailed.add(1);
        }
      } catch (_) {
        ocrFailed.add(1);
      }
    }
  });

  // No sleep — each VU fires again immediately after the response is received.
  // This ensures VU count == in-flight request count (since inference >> 0).
}
