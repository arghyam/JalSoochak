/**
 * Load test: Analytics-service dashboard endpoints
 *
 * Target  : analytics-service (port 8087 / routed via API gateway)
 * Auth    : None — all GET /api/v1/analytics/** endpoints are public
 * Profile : Ramp to 200 VUs steady, spike to 500, with a 30 s warm-up pass
 *           to prime the Redis cache before the main load starts.
 *
 * Environment variables:
 *   ANALYTICS_BASE_URL  Base URL (default: https://jalsoochak.beehyv.com/analytics)
 *   START_DATE          ISO date for query range start (default: 2024-01-01)
 *   END_DATE            ISO date for query range end   (default: 2024-12-31)
 *
 * Data file:
 *   load-tests/k6/data/tenants.csv  — columns: tenant_id, lgd_id
 */

import http from 'k6/http';
import { group, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { BASE_URLS, THRESHOLDS, ANALYTICS_STAGES, DATE_RANGE } from './lib/config.js';
import { trends, errorRates, recordResponse, qs } from './lib/helpers.js';

// ── Test data ─────────────────────────────────────────────────────────────────
const tenants = new SharedArray('tenants', function () {
  return open('./data/tenants.csv')
    .split('\n')
    .slice(1)                         // skip header row
    .filter((line) => line.trim())
    .map((line) => {
      const [tenant_id, lgd_id] = line.split(',');
      return { tenant_id: tenant_id.trim(), lgd_id: lgd_id.trim() };
    });
});

// ── k6 options ────────────────────────────────────────────────────────────────
export const options = {
  stages:     ANALYTICS_STAGES,
  thresholds: THRESHOLDS.analytics,
};

// ── Default function (one VU iteration) ──────────────────────────────────────
export default function () {
  // Pick a random tenant for this iteration
  const tenant = tenants[Math.floor(Math.random() * tenants.length)];
  const base   = `${BASE_URLS.analytics}/api/v1/analytics`;
  const dates  = { start_date: DATE_RANGE.start, end_date: DATE_RANGE.end };

  // 1. National dashboard — no tenant scoping, heaviest aggregation query
  group('national_dashboard', function () {
    const res = http.get(base + '/national/dashboard' + qs(dates), { tags: { endpoint: 'national_dashboard' } });
    recordResponse('national_dashboard', res, trends.nationalDashboard, errorRates.analytics);
  });

  sleep(0.5);

  // 2. Scheme regularity average — uses parent_lgd_id, not tenant_id
  group('scheme_regularity_average', function () {
    const res = http.get(
      base + '/scheme-regularity/average' + qs({ parent_lgd_id: tenant.lgd_id, ...dates }),
      { tags: { endpoint: 'scheme_regularity_average' } }
    );
    recordResponse('scheme_regularity_average', res, trends.schemeRegularity, errorRates.analytics);
  });

  sleep(0.5);

  // 3. Water quantity periodic (monthly) — uses lgd_id, not tenant_id
  group('water_quantity_periodic', function () {
    const res = http.get(
      base + '/water-quantity/periodic' + qs({ lgd_id: tenant.lgd_id, scale: 'month', ...dates }),
      { tags: { endpoint: 'water_quantity_periodic' } }
    );
    recordResponse('water_quantity_periodic', res, trends.waterQuantityPeriodic, errorRates.analytics);
  });

  sleep(0.5);

  // 4. Submission status — uses lgd_id, not tenant_id
  group('submission_status', function () {
    const res = http.get(
      base + '/submission-status' + qs({ lgd_id: tenant.lgd_id, ...dates }),
      { tags: { endpoint: 'submission_status' } }
    );
    recordResponse('submission_status', res, trends.submissionStatus, errorRates.analytics);
  });

  sleep(0.5);

  // 5. Schemes dashboard — uses parent_lgd_id, not tenant_id
  group('schemes_dashboard', function () {
    const res = http.get(
      base + '/schemes/dashboard' + qs({ parent_lgd_id: tenant.lgd_id, ...dates }),
      { tags: { endpoint: 'schemes_dashboard' } }
    );
    recordResponse('schemes_dashboard', res, trends.schemesDashboard, errorRates.analytics);
  });

  // Brief pause between iteration cycles to model realistic user think-time
  sleep(1);
}
