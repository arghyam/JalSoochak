/**
 * Central configuration for all k6 load test scripts.
 * Override any value via environment variables at runtime:
 *   k6 run -e ANALYTICS_BASE_URL=http://localhost:8087 script.js
 */

export const BASE_URLS = {
  // Scripts append /api/v1/analytics and /api/v1/telemetry respectively,
  // so these base URLs should NOT include those segments.
  analytics:  __ENV.ANALYTICS_BASE_URL  || 'https://staging.jalsoochak.in',
  telemetry:  __ENV.TELEMETRY_BASE_URL  || 'https://staging.jalsoochak.in',
  // FlowVision is a separate service behind its own path prefix.
  // Script appends /v1/extract-reading → full URL: staging.jalsoochak.in/flowvision/v1/extract-reading
  flowvision: __ENV.FLOWVISION_BASE_URL || 'https://staging.jalsoochak.in/flowvision',
};

// ── Thresholds ──────────────────────────────────────────────────────────────

export const THRESHOLDS = {
  analytics: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed:   ['rate<0.01'],
  },
  telemetry: {
    // Each /readings call chains: FlowVision OCR (up to 10s) + DB write + Kafka publish
    http_req_duration: ['p(95)<12000', 'p(99)<15000'],
    http_req_failed:   ['rate<0.02'],
  },
  flowvision: {
    http_req_duration: ['p(95)<3000', 'p(99)<5000'],
    http_req_failed:   ['rate<0.05'],
  },
};

// ── Load stage profiles ──────────────────────────────────────────────────────

/**
 * Analytics dashboard — read-heavy, Redis-cached.
 * Warm-up → ramp → steady → spike → ramp down.
 */
export const ANALYTICS_STAGES = [
  { duration: '30s', target: 1   }, // warm-up (cache priming)
  { duration: '2m',  target: 200 }, // ramp up
  { duration: '5m',  target: 200 }, // steady load
  { duration: '30s', target: 500 }, // spike
  { duration: '1m',  target: 500 }, // hold spike
  { duration: '30s', target: 200 }, // recover
  { duration: '2m',  target: 0   }, // ramp down
];

/**
 * Telemetry reading upload — expensive cascade (FlowVision + Kafka + DB write).
 * Lower VU ceiling to avoid saturating downstream services.
 */
export const TELEMETRY_STAGES = [
  { duration: '1m',  target: 1   }, // smoke
  { duration: '2m',  target: 50  }, // ramp up
  { duration: '5m',  target: 50  }, // steady load
  { duration: '30s', target: 150 }, // spike
  { duration: '1m',  target: 150 }, // hold spike
  { duration: '30s', target: 50  }, // recover
  { duration: '2m',  target: 0   }, // ramp down
];

/**
 * FlowVision direct — conservative; external AI service with quota.
 */
export const FLOWVISION_STAGES = [
  { duration: '30s', target: 1  }, // smoke
  { duration: '1m',  target: 30 }, // ramp up
  { duration: '3m',  target: 30 }, // steady load
  { duration: '30s', target: 80 }, // spike
  { duration: '1m',  target: 80 }, // hold spike
  { duration: '30s', target: 30 }, // recover
  { duration: '1m',  target: 0  }, // ramp down
];

// ── Date range used across all tests ────────────────────────────────────────
// Adjust to a window that has real data in staging.
export const DATE_RANGE = {
  start: __ENV.START_DATE || '2024-01-01',
  end:   __ENV.END_DATE   || '2024-12-31',
};
