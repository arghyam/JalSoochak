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
 *   TELEMETRY_BASE_URL  Base URL (default: https://staging.jalsoochak.in)
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
  const ops = open('./data/operators.csv')
    .split('\n')
    .slice(1)
    .filter((line) => line.trim())
    .map((line) => line.trim());

  if (ops.length === 0) {
    throw new Error('operators.csv is empty or malformed — no valid operator contactIds found');
  }

  return ops;
});

const imageUrls = new SharedArray('imageUrls', function () {
  const urls = open('./data/image-urls.csv')
    .split('\n')
    .slice(1)
    .filter((line) => line.trim())
    .map((line) => line.trim());

  if (urls.length === 0) {
    throw new Error('image-urls.csv is empty or malformed — no valid image URLs found');
  }

  return urls;
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
  // Deterministic per-iteration rotation using VU and iteration counters with prime stride
  const operatorIndex = ((__VU - 1) * 1009 + __ITER) % operators.length;
  const contactId = operators[operatorIndex];
  const mediaUrl  = imageUrls[Math.floor(Math.random() * imageUrls.length)];

  // ── Path A: image upload with OCR (main production flow) ─────────────────
  group('readings_image_ocr', function () {
    const payload = JSON.stringify({
      contactId:      contactId,
      messageType:    'IMAGE',
      mediaUrl:       mediaUrl,
      isMeterReplaced: false,
    });

    const res = http.post(base + '/readings/glific', payload, {
      headers: HEADERS,
      tags:    { endpoint: 'readings_image_ocr' },
    });

    // HTTP 5xx = failure; qualityStatus REJECTED is a valid business outcome, not a failure
    // Accept 200 or duplicate-reading 400 (specific error message check)
    let isDuplicateError = false;
    let parsedBody = null;
    if (res.status === 400 && res.body) {
      try {
        parsedBody = JSON.parse(res.body);
        const errorCode = parsedBody.error && parsedBody.error.code;
        const errorMessage = parsedBody.message || parsedBody.error && parsedBody.error.message || '';
        isDuplicateError = errorCode === 'DUPLICATE_READING' ||
                          errorCode === 'duplicate' ||
                          errorMessage.toLowerCase().includes('duplicate');
      } catch (_) {
        // Fallback: if JSON parsing fails, check raw body
        isDuplicateError = res.body.toLowerCase().includes('duplicate');
      }
    }
    const ok = check(res, {
      'readings_ocr: status 200 or duplicate 400': (r) => r.status === 200 || isDuplicateError,
    });
    trends.telemetryReadings.add(res.timings.duration);
    // Count 5xx errors OR non-duplicate 4xx errors OR transport failures as failures
    const isError = res.status >= 500 || (res.status >= 400 && res.status < 500 && !isDuplicateError) || (res.status === 0 && res.error);
    errorRates.telemetry.add(isError);

    if (res.status === 200) {
      try {
        const body = parsedBody || JSON.parse(res.body);
        if (body.qualityStatus) {
          if (body.qualityStatus === 'REJECTED' || body.qualityStatus === 'UNREADABLE') {
            rejectedReadings.add(1);
          } else {
            confirmedReadings.add(1);
          }
        }
        // If no qualityStatus, it's an ack payload — not a confirmed reading
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
