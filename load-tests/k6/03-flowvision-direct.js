/**
 * Load test: FlowVision AI extraction service (direct)
 *
 * Purpose : Isolate FlowVision latency and throughput independently from the
 *           telemetry service overhead. Use this to answer:
 *           "Is telemetry slow because of us, or because FlowVision is slow?"
 *
 * Target  : POST https://staging.jalsoochak.in/flowvision/v1/extract-reading
 * Auth    : None (internal service, no API key required)
 * Profile : Conservative — ramp to 30 VUs steady, spike to 80.
 *           External AI service — run with care to avoid exhausting quota.
 *
 * Environment variables:
 *   FLOWVISION_BASE_URL  Base URL (default: https://staging.jalsoochak.in/flowvision)
 *
 * Data file:
 *   load-tests/k6/data/image-urls.csv — column: imageUrl
 *   These must be MinIO URLs accessible to the FlowVision service.
 */

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { BASE_URLS, THRESHOLDS, FLOWVISION_STAGES } from './lib/config.js';
import { trends, errorRates } from './lib/helpers.js';
import { Counter } from 'k6/metrics';

// ── Custom counters ───────────────────────────────────────────────────────────
const ocrSuccess = new Counter('flowvision_ocr_success');
const ocrFailed  = new Counter('flowvision_ocr_failed');

// ── Test data ─────────────────────────────────────────────────────────────────
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
  stages:     FLOWVISION_STAGES,
  thresholds: THRESHOLDS.flowvision,
};

const HEADERS = { 'Content-Type': 'application/json' };

// ── Default function ──────────────────────────────────────────────────────────
export default function () {
  const imageUrl = imageUrls[Math.floor(Math.random() * imageUrls.length)];

  group('flowvision_extract_reading', function () {
    const payload = JSON.stringify({ imageURL: imageUrl });

    const res = http.post(
      `${BASE_URLS.flowvision}/v1/extract-reading`,
      payload,
      { headers: HEADERS, tags: { endpoint: 'flowvision_extract_reading' } }
    );

    // HTTP 200 + result.status == SUCCESS is the only true success.
    // A 200 with result.status != SUCCESS means the OCR model could not read the image.
    const httpOk = check(res, {
      'flowvision: status 200': (r) => r.status === 200,
    });

    trends.flowvisionExtract.add(res.timings.duration);
    errorRates.flowvision.add(!httpOk);

    if (httpOk) {
      try {
        const body   = res.json();
        const status = body?.result?.status;
        if (status === 'SUCCESS') {
          ocrSuccess.add(1);
          // Verify expected response shape
          check(body, {
            'flowvision: has correlationId':      (b) => !!b?.result?.correlationId,
            'flowvision: has meterReading':       (b) => b?.result?.data?.meterReading != null,
            'flowvision: has qualityConfidence':  (b) => b?.result?.data?.qualityConfidence != null,
          });
        } else {
          ocrFailed.add(1);
          errorRates.flowvision.add(1);
        }
      } catch (_) {
        ocrFailed.add(1);
        errorRates.flowvision.add(1);
      }
    }
  });

  // Slight pause — avoid hammering the AI model between back-to-back requests
  sleep(0.5);
}
