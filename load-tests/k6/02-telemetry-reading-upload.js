/**
 * Load test: Telemetry-service BFM reading upload
 *
 * Target  : telemetry-service (port 8989 / routed via API gateway)
 * Auth    : None — Keycloak config is commented out in telemetry-service
 * Profile : Ramp to 50 VUs steady, spike to 150.
 *           Each POST /readings triggers: FlowVision OCR (5-10 s) + DB write + Kafka publish.
 *           POST /manual-reading is also exercised to isolate DB/Kafka-only cost.
 *
 * Environment variables:
 *   TELEMETRY_BASE_URL  Base URL (default: https://jalsoochak.beehyv.com/telemetry)
 *
 * Data files:
 *   load-tests/k6/data/operators.csv   — column: contactId
 *   load-tests/k6/data/image-urls.csv  — column: imageUrl
 *
 * IMPORTANT: contactIds in operators.csv must map to real operators in the staging DB.
 * Using a unique contactId per iteration prevents the duplicate-reading anomaly check
 * from flagging test submissions as duplicates.
 */

import http from 'k6/http';
import { group, check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { BASE_URLS, THRESHOLDS, TELEMETRY_STAGES } from './lib/config.js';
import { trends, errorRates, recordResponse } from './lib/helpers.js';
import { Counter } from 'k6/metrics';

// ── Custom counters ───────────────────────────────────────────────────────────
const rejectedReadings = new Counter('telemetry_rejected_readings');
const confirmedReadings = new Counter('telemetry_confirmed_readings');

// ── Test data ─────────────────────────────────────────────────────────────────
const operators = new SharedArray('operators', function () {
  return open('./data/operators.csv')
    .split('\n')
    .slice(1)
    .filter((line) => line.trim())
    .map((line) => line.trim());
});

const imageUrls = new SharedArray('imageUrls', function () {
  return open('./data/image-urls.csv')
    .split('\n')
    .slice(1)
    .filter((line) => line.trim())
    .map((line) => line.trim());
});

// ── k6 options ────────────────────────────────────────────────────────────────
export const options = {
  stages:     TELEMETRY_STAGES,
  thresholds: THRESHOLDS.telemetry,
};

const HEADERS = { 'Content-Type': 'application/json' };

// ── Default function ──────────────────────────────────────────────────────────
export default function () {
  const base      = `${BASE_URLS.telemetry}/api/v1/telemetry`;
  const contactId = operators[Math.floor(Math.random() * operators.length)];
  const mediaUrl  = imageUrls[Math.floor(Math.random() * imageUrls.length)];

  // ── Path A: image upload with OCR (main production flow) ─────────────────
  group('readings_image_ocr', function () {
    const payload = JSON.stringify({
      contactId:      contactId,
      messageType:    'IMAGE',
      mediaUrl:       mediaUrl,
      isMeterReplaced: false,
    });

    const res = http.post(base + '/readings', payload, {
      headers: HEADERS,
      tags:    { endpoint: 'readings_image_ocr' },
    });

    // HTTP 5xx = failure; qualityStatus REJECTED is a valid business outcome, not a failure
    const ok = check(res, {
      'readings_ocr: status 200 or 400': (r) => r.status === 200 || r.status === 400,
    });
    trends.telemetryReadings.add(res.timings.duration);
    errorRates.telemetry.add(res.status >= 500);

    if (res.status === 200) {
      try {
        const body = res.json();
        if (body.qualityStatus === 'REJECTED' || body.qualityStatus === 'UNREADABLE') {
          rejectedReadings.add(1);
        } else {
          confirmedReadings.add(1);
        }
      } catch (_) {
        // non-JSON body — already counted via errorRates above
      }
    }
  });

  sleep(2);

  // ── Path B: manual reading (no OCR — isolates DB + Kafka latency) ─────────
  group('manual_reading', function () {
    // ManualReadingRequest requires contactId and a numeric readingValue as a string
    const payload = JSON.stringify({
      contactId:     contactId,
      manualReading: String((Math.random() * 900 + 100).toFixed(2)), // random 100–1000
    });

    const res = http.post(base + '/manual-reading', payload, {
      headers: HEADERS,
      tags:    { endpoint: 'manual_reading' },
    });

    recordResponse('manual_reading', res, trends.telemetryManualReading, errorRates.telemetry);
  });

  sleep(1);
}
