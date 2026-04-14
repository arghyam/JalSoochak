import { check, group } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// Per-endpoint latency trends — visible in Grafana and k6 summary
export const trends = {
  // Analytics
  nationalDashboard:      new Trend('analytics_national_dashboard_ms',      true),
  schemeRegularity:       new Trend('analytics_scheme_regularity_ms',       true),
  waterQuantityPeriodic:  new Trend('analytics_water_quantity_periodic_ms', true),
  submissionStatus:       new Trend('analytics_submission_status_ms',       true),
  schemesDashboard:       new Trend('analytics_schemes_dashboard_ms',       true),

  // Telemetry
  telemetryReadings:      new Trend('telemetry_readings_ms',      true),
  telemetryManualReading: new Trend('telemetry_manual_reading_ms', true),

  // FlowVision
  flowvisionExtract:      new Trend('flowvision_extract_ms', true),
};

// Per-endpoint error rates
export const errorRates = {
  analytics:  new Rate('analytics_errors'),
  telemetry:  new Rate('telemetry_errors'),
  flowvision: new Rate('flowvision_errors'),
};

/**
 * Wraps a single HTTP response with standard checks and metric recording.
 *
 * @param {string} label     - Human-readable label for the check
 * @param {object} res       - k6 HTTP response object
 * @param {Trend}  trend     - Per-endpoint Trend metric to record duration into
 * @param {Rate}   errorRate - Per-service Rate metric to record failures into
 * @param {object} [extraChecks] - Additional check predicates beyond status 200
 */
export function recordResponse(label, res, trend, errorRate, extraChecks = {}) {
  const ok = check(res, {
    [`${label}: status 200`]: (r) => r.status === 200,
    ...extraChecks,
  });
  trend.add(res.timings.duration);
  errorRate.add(!ok);
}

/**
 * Builds a query-string from an object, omitting null/undefined values.
 *
 * @param {object} params
 * @returns {string} e.g. "?tenant_id=1&start_date=2024-01-01&end_date=2024-12-31"
 */
export function qs(params) {
  const parts = Object.entries(params)
    .filter(([, v]) => v != null)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
  return parts.length ? '?' + parts.join('&') : '';
}

/**
 * Returns a random integer in [min, max).
 */
export function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min)) + min;
}

/**
 * Returns a random element from an array.
 */
export function pick(arr) {
  return arr[randomInt(0, arr.length)];
}
