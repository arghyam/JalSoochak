package org.arghyam.jalsoochak.telemetry.service.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryGeoPoint;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantAnomalyRecord;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.AnomalyConstants;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * LOCATION-AFFINITY: gathers a scheme's position and the configured radius, runs
 * {@link LocationAffinityPolicy}, and owns both the observability and the anomaly write for the
 * result.
 *
 * <p>It sits between the submission paths and the policy so that <strong>every</strong>
 * {@code LOCATION_MISMATCH} row in the platform is created in one place. There are four call sites
 * — the {@code /location} webhook, the image/API reading funnel, and the manual-reading path — and
 * the alternative was the same forty-line anomaly block copied into each. That matters more here
 * than for the supply guard, whose caller had to own the write because it held the baseline reading
 * the row needed; a location mismatch carries no reading-derived numbers at all.
 *
 * <p><strong>The distance never reaches an API response.</strong> Callers receive only which arm of
 * {@link LocationVerdict} came back; the metres go into the log line and the metric here. That is
 * not a disclosure concern as it is for the supply ceiling — a radius reveals nothing about another
 * scheme — but keeping the shape identical means nobody has to work out which of the two guards
 * they are looking at.
 *
 * <p><strong>Writing the anomaly is best-effort.</strong> {@code createReading} is not
 * transactional, and a boundary observation is never worth losing a meter reading over, so a failed
 * write warns and the verdict is still returned.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocationAffinityService {

    private static final String METRIC_SKIPPED = "location_affinity.skipped";
    private static final String METRIC_WITHIN = "location_affinity.within";
    private static final String METRIC_MISMATCH = "location_affinity.mismatch";

    /** Per-tenant on/off switch, {@code {"value":"YES"}}. Governs the state-IT API as well as WhatsApp. */
    private static final String LOCATION_CHECK_REQUIRED = "LOCATION_CHECK_REQUIRED";

    /**
     * The radius, in metres. A <em>system</em> key: it lives at {@code tenant_id = 0} and is managed
     * by a Super Admin, so every tenant shares one value. Telemetry already reads system config this
     * way for {@code WATER_QUANTITY_SUPPLY_THRESHOLD}. Should a per-tenant radius ever be wanted,
     * {@link #resolveThresholdMetres} is where a {@code TENANT_}-prefixed tier goes in front.
     */
    private static final String LOCATION_AFFINITY_THRESHOLD = "LOCATION_AFFINITY_THRESHOLD";

    /** The tenant id system-level configuration is stored under. */
    private static final int SYSTEM_TENANT_ID = 0;

    /** Rejects anything a {@code BigDecimal} would accept but a distance in metres should not. */
    private static final String POSITIVE_DECIMAL = "^\\d+(\\.\\d+)?$";

    /** Which submission path asked. A metric tag, so the values are fixed and few. */
    public enum Path {
        /** {@code POST /location} — the WhatsApp location message, before any reading exists. */
        LOCATION_WEBHOOK("location_webhook"),
        /** {@code POST /readings/glific} — an operator's meter photo. */
        IMAGE_SUBMISSION("image_submission"),
        /** {@code POST /manual-reading} — an operator typing the value in. */
        MANUAL_READING("manual_reading"),
        /** {@code POST /readings} — the state IT system's integration. */
        STATE_API("state_api");

        private final String tag;

        Path(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final TenantConfigRepository tenantConfigRepository;
    private final ObjectMapper objectMapper;
    private final TelemetryEventPublisher telemetryEventPublisher;
    private final MeterRegistry meterRegistry;

    /**
     * Evaluates a submission's location without recording anything.
     *
     * <p>For the {@code /location} webhook, which must be able to warn the operator <em>before</em>
     * any reading exists. Recording here would create an anomaly for an operator who then answers
     * "No" and walks away.
     */
    public LocationVerdict assess(String schemaName,
                                  Integer tenantId,
                                  Long schemeId,
                                  BigDecimal readingLatitude,
                                  BigDecimal readingLongitude,
                                  Path path) {
        LocationVerdict verdict = evaluate(
                schemaName, tenantId, schemeId, readingLatitude, readingLongitude);
        report(verdict, tenantId, null, schemeId, null, path);
        return verdict;
    }

    /**
     * Evaluates a persisted reading's location and, when it is out of bounds, records the anomaly
     * against it.
     *
     * <p>On the WhatsApp path this is how the operator's "Yes" is observed. The backend is never
     * told the answer: a confirmation is a reading subsequently arriving, and a decline leaves
     * nothing behind but the placeholder row {@code /location} already wrote.
     *
     * <p>Coordinates are resolved in one of two ways, which is what lets both submission channels
     * share this method. The state-IT API carries them on the request, so they are passed in. The
     * WhatsApp paths do not — {@code /location} wrote them onto the placeholder row minutes earlier
     * and the reading reused it — so passing {@code null} makes the service read them back off
     * {@code submission.readingId()}.
     *
     * @param requestLatitude  coordinates supplied with the reading itself, or {@code null} to read
     *                         whatever a previous request stored on the row
     * @param requestLongitude see {@code requestLatitude}
     */
    public LocationVerdict recordMismatchIfAny(String schemaName,
                                               Integer tenantId,
                                               Long operatorId,
                                               Long schemeId,
                                               ReadingSubmission submission,
                                               BigDecimal requestLatitude,
                                               BigDecimal requestLongitude,
                                               Path path) {
        BigDecimal latitude = requestLatitude;
        BigDecimal longitude = requestLongitude;
        if ((latitude == null || longitude == null) && submission != null && submission.readingId() != null) {
            Optional<TelemetryGeoPoint> stored = storedReadingLocation(schemaName, submission.readingId());
            if (stored.isPresent() && stored.get().isComplete()) {
                latitude = BigDecimal.valueOf(stored.get().latitude());
                longitude = BigDecimal.valueOf(stored.get().longitude());
            }
        }

        LocalDate readingDate = submission != null ? submission.readingDate() : null;
        LocationVerdict verdict = evaluate(schemaName, tenantId, schemeId, latitude, longitude);
        report(verdict, tenantId, operatorId, schemeId, readingDate, path);

        if (verdict instanceof LocationVerdict.Outside) {
            recordAnomaly(schemaName, tenantId, operatorId, schemeId, submission);
        }
        return verdict;
    }

    /**
     * Reads the coordinates a previous request stored on a reading row.
     *
     * <p>The WhatsApp path splits the location and the reading across two requests:
     * {@code /location} writes the coordinates onto a placeholder row and the image submission
     * reuses that row, so by the time the reading is persisted the coordinates are only on the row.
     */
    private Optional<TelemetryGeoPoint> storedReadingLocation(String schemaName, Long readingId) {
        try {
            return telemetryTenantRepository.findReadingLocation(schemaName, readingId);
        } catch (Exception e) {
            log.warn("location_affinity_reading_lookup_failed readingId={}: {}", readingId, e.getMessage());
            return Optional.empty();
        }
    }

    private LocationVerdict evaluate(String schemaName,
                                     Integer tenantId,
                                     Long schemeId,
                                     BigDecimal readingLatitude,
                                     BigDecimal readingLongitude) {
        boolean checkRequired = isLocationCheckRequired(tenantId);

        // Short-circuit before touching the database or the scheme row: when the tenant has not
        // opted in there is nothing to gather, and the skip counter should measure the switch
        // rather than whatever else happens to be missing.
        if (!checkRequired) {
            return new LocationVerdict.Skipped(LocationVerdict.SkipReason.CHECK_NOT_REQUIRED);
        }

        Double readingLat = toDouble(readingLatitude);
        Double readingLng = toDouble(readingLongitude);
        if (readingLat == null || readingLng == null) {
            return new LocationVerdict.Skipped(LocationVerdict.SkipReason.NO_READING_LOCATION);
        }

        TelemetryGeoPoint schemePoint = findSchemeLocation(schemaName, schemeId)
                .orElse(new TelemetryGeoPoint(null, null));

        return LocationAffinityPolicy.evaluate(new LocationAffinityInputs(
                true,
                readingLat,
                readingLng,
                schemePoint.latitude(),
                schemePoint.longitude(),
                resolveThresholdMetres().map(BigDecimal::doubleValue).orElse(null)));
    }

    private Optional<TelemetryGeoPoint> findSchemeLocation(String schemaName, Long schemeId) {
        try {
            return telemetryTenantRepository.findSchemeLocation(schemaName, schemeId);
        } catch (Exception e) {
            // A lookup failure must read as "no opinion", never as a mismatch.
            log.warn("location_affinity_scheme_lookup_failed schemeId={}: {}", schemeId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The configured radius in metres.
     *
     * <p>One tier today — the system value at {@code tenant_id = 0}. A per-tenant override would be
     * added in front of it here, mirroring the
     * {@code TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD → WATER_QUANTITY_SUPPLY_THRESHOLD} chain in
     * {@code BfmReadingService}; nothing else would need to change.
     */
    private Optional<BigDecimal> resolveThresholdMetres() {
        return readPositiveDecimalConfig(SYSTEM_TENANT_ID, LOCATION_AFFINITY_THRESHOLD);
    }

    private boolean isLocationCheckRequired(Integer tenantId) {
        return safeFindConfigValue(tenantId, LOCATION_CHECK_REQUIRED)
                .flatMap(this::extractConfigValue)
                .map(value -> value.trim().equalsIgnoreCase("YES"))
                .orElse(false);
    }

    private Optional<BigDecimal> readPositiveDecimalConfig(Integer tenantId, String key) {
        Optional<String> value = safeFindConfigValue(tenantId, key).flatMap(this::extractConfigValue);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String normalized = value.get().trim().replace(",", "");
        if (!normalized.matches(POSITIVE_DECIMAL)) {
            log.warn("Invalid {} config for tenantId {}; boundary check will be skipped", key, tenantId);
            return Optional.empty();
        }
        BigDecimal parsed = new BigDecimal(normalized);
        return parsed.signum() > 0 ? Optional.of(parsed) : Optional.empty();
    }

    /**
     * Unwraps a {@code SimpleConfigValueDTO} row, which may be {@code {"value":"150"}}, a bare JSON
     * string, or an unquoted scalar left over from a hand-written row.
     */
    private Optional<String> extractConfigValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node != null) {
                if (node.isTextual()) {
                    return Optional.ofNullable(node.asText()).filter(value -> !value.isBlank());
                }
                JsonNode valueNode = node.get("value");
                if (valueNode != null && !valueNode.isNull()) {
                    return Optional.ofNullable(valueNode.asText()).filter(value -> !value.isBlank());
                }
                if (node.isNumber()) {
                    return Optional.of(node.asText());
                }
            }
        } catch (Exception ignored) {
            // Not JSON. Fall through to the raw string, which is how older rows were written.
        }
        return Optional.of(raw.trim());
    }

    /**
     * {@code findConfigValue} is declared to return an {@code Optional}, but it is {@code @MockBean}-ed
     * in tests that do not stub it and then hands back a literal {@code null}. Mirrors
     * {@code BfmReadingService.safeFindConfigValue}.
     */
    private Optional<String> safeFindConfigValue(Integer tenantId, String key) {
        if (tenantId == null) {
            return Optional.empty();
        }
        try {
            Optional<String> value = tenantConfigRepository.findConfigValue(tenantId, key);
            return value == null ? Optional.empty() : value;
        } catch (Exception e) {
            log.warn("Could not read {} for tenantId {}: {}", key, tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private void recordAnomaly(String schemaName,
                               Integer tenantId,
                               Long operatorId,
                               Long schemeId,
                               ReadingSubmission submission) {
        LocalDate readingDate = submission != null ? submission.readingDate() : null;
        try {
            // The tenant table has no correlation_id and therefore no dedup of its own, so an
            // operator retrying a blurry image would otherwise leave one row per attempt. Analytics
            // is deduped by the derived uuid below; this is the operational table's equivalent.
            if (telemetryTenantRepository.countAnomaliesByTypeForToday(
                    schemaName, operatorId, schemeId, AnomalyConstants.TYPE_LOCATION_MISMATCH) == 0) {
                telemetryTenantRepository.createTenantAnomalyRecord(schemaName, TenantAnomalyRecord.builder()
                        .userId(operatorId)
                        .schemeId(schemeId)
                        .type(AnomalyConstants.TYPE_LOCATION_MISMATCH)
                        .reason(AnomalyConstants.REASON_LOCATION_MISMATCH)
                        .status(AnomalyConstants.STATUS_OPEN)
                        .retries(0)
                        // ANOMALY-SUBMISSION-LINK: unlike the types raised before anything is
                        // written, this one always has a stored reading to point at — that is what
                        // "captured against this submission" means operationally.
                        .flowReadingId(submission != null ? submission.readingId() : null)
                        .build());
            }

            telemetryEventPublisher.publishAnomalyRecorded(
                    tenantId,
                    AnomalyConstants.TYPE_LOCATION_MISMATCH,
                    operatorId,
                    schemeId,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    0,
                    AnomalyConstants.REASON_LOCATION_MISMATCH,
                    AnomalyConstants.STATUS_OPEN,
                    anomalyCorrelationId(operatorId, schemeId, readingDate),
                    // The warehouse cannot use flow_reading_id — it is schema-local — so it joins
                    // fact_meter_reading_table on the submission's correlation_id instead.
                    submission != null ? submission.correlationId() : null);
        } catch (Exception e) {
            // Never fail a reading over an anomaly. createReading is not transactional, so throwing
            // here would abandon a submission the operator has already been told was accepted.
            log.warn("location_mismatch_anomaly_write_failed tenantId={} schemeId={} operatorId={}: {}",
                    tenantId, schemeId, operatorId, e.getMessage());
        }
    }

    /**
     * One anomaly per operator, per scheme, per day.
     *
     * <p>{@code TelemetryEventPublisher} derives the event uuid from this plus the user id, and
     * {@code FactServiceImpl} touches rather than inserts when that uuid already exists — so a
     * second submission on the same day updates the timestamp instead of adding a row. Same
     * construction as {@code BfmReadingService.buildSupplyAnomalyCorrelationId}.
     */
    private static String anomalyCorrelationId(Long operatorId, Long schemeId, LocalDate readingDate) {
        String key = AnomalyConstants.TYPE_LOCATION_MISMATCH + ":" + operatorId + ":" + schemeId
                + ":" + readingDate;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /**
     * The one place a verdict's numbers are written down. The measured distance and the threshold
     * appear here and nowhere else: the anomaly row has no numeric column for them and its reason
     * text is fixed so the column stays groupable. The distance stays recomputable from
     * {@code flow_reading_table} and {@code scheme_master_table}, which is why not persisting it
     * costs nothing.
     */
    private void report(LocationVerdict verdict,
                        Integer tenantId,
                        Long operatorId,
                        Long schemeId,
                        LocalDate readingDate,
                        Path path) {
        switch (verdict) {
            case LocationVerdict.Skipped skipped -> {
                count(METRIC_SKIPPED, path, "reason", skipped.reason().metricTag());
                if (skipped.reason() == LocationVerdict.SkipReason.NO_SCHEME_LOCATION) {
                    // A fixable master-data gap rather than a property of the submission. Loud on
                    // purpose: while this fires the check is a no-op for every reading on the scheme.
                    log.warn("location_affinity_skipped reason={} tenantId={} schemeId={} path={} "
                                    + "— scheme has no usable latitude/longitude",
                            skipped.reason().metricTag(), tenantId, schemeId, path.tag());
                } else {
                    log.debug("location_affinity_skipped reason={} tenantId={} schemeId={} path={}",
                            skipped.reason().metricTag(), tenantId, schemeId, path.tag());
                }
            }
            case LocationVerdict.Within within -> {
                count(METRIC_WITHIN, path);
                log.debug("location_affinity_within tenantId={} schemeId={} path={} distanceMetres={} thresholdMetres={}",
                        tenantId, schemeId, path.tag(),
                        Math.round(within.distanceMetres()), Math.round(within.thresholdMetres()));
            }
            case LocationVerdict.Outside outside -> {
                count(METRIC_MISMATCH, path);
                log.warn("location_mismatch tenantId={} schemeId={} operatorId={} readingDate={} path={} "
                                + "distanceMetres={} thresholdMetres={}",
                        tenantId, schemeId, operatorId, readingDate, path.tag(),
                        Math.round(outside.distanceMetres()), Math.round(outside.thresholdMetres()));
            }
        }
    }

    private void count(String metric, Path path, String... extraTags) {
        if (meterRegistry == null) {
            return;
        }
        Counter.Builder builder = Counter.builder(metric).tag("path", path.tag());
        for (int i = 0; i + 1 < extraTags.length; i += 2) {
            builder = builder.tag(extraTags[i], extraTags[i + 1]);
        }
        builder.register(meterRegistry).increment();
    }
}
