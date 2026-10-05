package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.channel.ReadingUnit;
import org.arghyam.jalsoochak.telemetry.channel.ReportingChannel;
import org.arghyam.jalsoochak.telemetry.config.TenantContext;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.DailyConfirmedReading;
import org.arghyam.jalsoochak.telemetry.repository.FlowReadingVersion;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantAnomalyRecord;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.capture.CaptureInput;
import org.arghyam.jalsoochak.telemetry.service.capture.CaptureOutcome;
import org.arghyam.jalsoochak.telemetry.service.capture.CapturedReading;
import org.arghyam.jalsoochak.telemetry.service.capture.ImageReadingCapture;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimit;
import org.arghyam.jalsoochak.telemetry.service.capture.ReadingCapture;
import org.arghyam.jalsoochak.telemetry.service.capture.SubmittedValueCapture;
import org.arghyam.jalsoochak.telemetry.service.location.LocationAffinityService;
import org.arghyam.jalsoochak.telemetry.service.location.ReadingSubmission;
import org.arghyam.jalsoochak.telemetry.service.water.QuarantineReason;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityGuard;
import org.arghyam.jalsoochak.telemetry.service.water.Verdict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class BfmReadingService {

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final TelemetryEventPublisher telemetryEventPublisher;
    private final ReadingRepublisher readingRepublisher;
    private final TenantConfigRepository tenantConfigRepository;
    private final ObjectMapper objectMapper;
    private final OperatorContextService operatorContextService;
    private final ReadingChannelResolver readingChannelResolver;
    private final RolloverResolutionService rolloverResolutionService;
    private final SupplyPlausibilityGuard supplyPlausibilityGuard;
    private final ImageReadingCapture imageReadingCapture;
    private final SubmittedValueCapture submittedValueCapture;
    private final PduDayLimit pduDayLimit;
    private final CalculationParametersSnapshotter calculationParametersSnapshotter;
    // LOCATION-AFFINITY: the scheme-boundary check. Nullable so a unit test that does not exercise it
    // may pass null, and the check is then simply not run rather than costing the reading.
    private final LocationAffinityService locationAffinityService;

    /**
     * Trailing-history window (days) fetched for the rollover consumption band. A few extra days over
     * the resolver's 14-delta window so 14 consecutive-day deltas survive diffing.
     */
    private static final int ROLLOVER_HISTORY_DAYS = 18;

    /**
     * Single answer for "no operator here": used both when the contact is unknown and when it belongs
     * to another tenant, so the two cases stay indistinguishable to a caller probing phone numbers.
     */
    private static final String OPERATOR_LOOKUP_MISS = "No reading found for operator";

    public CreateReadingResponse createReading(CreateReadingRequest request,
                                               String schemaName,
                                               TelemetryOperator operator,
                                               String contactId,
                                               boolean isMeterReplaced) {
        return createReading(request, schemaName, operator, contactId, isMeterReplaced, OcrRetryMode.NONE);
    }

    public CreateReadingResponse createReading(CreateReadingRequest request,
                                               String schemaName,
                                               TelemetryOperator operator,
                                               String contactId,
                                               boolean isMeterReplaced,
                                               OcrRetryMode ocrRetryMode) {
        if (!telemetryTenantRepository.existsSchemeById(schemaName, request.getSchemeId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "State scheme not found");
        }

        TelemetryOperator operatorInRequest = telemetryTenantRepository
                .findOperatorById(schemaName, request.getOperatorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Operator not found"));
        Integer tenantId = operatorInRequest.tenantId();

        // LENIENT-INGEST: submissions recorded through the lenient path (missing scheme / missing
        // operator / operator-not-mapped) carry a non-zero ingestionSource. For those we skip the
        // operator-to-scheme mapping guard so the reading is still recorded and counted.
        boolean lenientIngestion = request.getIngestionSource() != null
                && request.getIngestionSource() != IngestionSource.NORMAL;

        boolean belongsToScheme = telemetryTenantRepository
                .isOperatorMappedToScheme(schemaName, operatorInRequest.id(), request.getSchemeId());

        if (!belongsToScheme && !lenientIngestion) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operator does not belong to the specified scheme");
        }
        ReadingCapture capture = captureFor(request);

        // The channel decides which units the submission may use and how its photo is read, so it is
        // settled before anything is captured. A channel declared on the submission wins over the
        // operator's stored preference: the submitting system knows the equipment behind this
        // particular reading, whereas the preference is a default picked once in a WhatsApp
        // conversation. Null means nothing was declared, which keeps the preference lookup, and so
        // every existing caller, unchanged.
        ReadingChannel resolvedChannel = request.getDeclaredChannel() != null
                ? request.getDeclaredChannel()
                : readingChannelResolver.resolve(schemaName, contactId);
        Integer channel = resolvedChannel.getCode();

        CaptureOutcome outcome = capture.capture(new CaptureInput(
                schemaName,
                tenantId,
                operatorInRequest.id(),
                request.getSchemeId(),
                resolvedChannel,
                request.getReadingUrl(),
                request.getReadingValue(),
                request.getReadingUnit(),
                request.isExternallyAsserted(),
                ocrRetryMode));
        CapturedReading captured;
        switch (outcome) {
            case CaptureOutcome.Captured(CapturedReading reading) -> captured = reading;
            case CaptureOutcome.Rejected(TelemetryErrorCode errorCode, String rejection) -> {
                return rejected(errorCode, rejection);
            }
            case CaptureOutcome.Retry(String retry) -> {
                return CreateReadingResponse.builder()
                        .success(false)
                        .message(retry)
                        .correlationId(UUID.randomUUID().toString())
                        .qualityStatus("RETRY")
                        .build();
            }
        }
        OcrReadingResult ocrResult = captured.ocrResult();
        BigDecimal finalReading = captured.value();
        BigDecimal confidenceLevel = captured.confidence();

        boolean hasPositiveReading = finalReading != null
                && finalReading.compareTo(BigDecimal.ZERO) > 0;
        boolean hasAcceptableConfidence = confidenceLevel == null
                || confidenceLevel.compareTo(BigDecimal.valueOf(0.7)) >= 0;
        boolean isValid = hasPositiveReading && hasAcceptableConfidence;

        String storageCorrelationId = Optional.ofNullable(ocrResult)
                .map(OcrReadingResult::getRequestId)
                .filter(value -> !value.isBlank())
                .orElse(UUID.randomUUID().toString());
        String responseCorrelationId = Optional.ofNullable(ocrResult)
                .map(OcrReadingResult::getCorrelationId)
                .filter(value -> !value.isBlank())
                .orElse(storageCorrelationId);
        String ocrCorrelationId = Optional.ofNullable(ocrResult)
                .map(OcrReadingResult::getCorrelationId)
                .filter(value -> !value.isBlank())
                .orElse(null);
        LocalDateTime readingAt = Optional.ofNullable(request.getReadingTime()).orElse(ReadingTime.now());

        // READING-PROVENANCE: extracted_reading records what the OCR provider read off the meter photo. The
        // image capture runs only when the caller supplied no value, so on an API-asserted submission
        // nothing extracted anything — echoing the caller's own number back into extracted_reading made
        // such a row indistinguishable from an AI-extracted one, fed the duplicate-image guard below a
        // value no image ever produced, and counted the submission as "compliant" (extracted ==
        // confirmed) on the dashboards. ocrExtractedReading is null on that path and gates both; the
        // persisted column is NOT NULL, so it takes the same 0 sentinel every other non-OCR row already
        // uses (scheme-selection placeholder, manual entry, meter-change, issue-report).
        BigDecimal ocrExtractedReading = captured.extractedReading();
        BigDecimal extractedReading = ocrExtractedReading != null ? ocrExtractedReading : BigDecimal.ZERO;
        // In the channel's standard unit already, so every comparison below and the stored value agree.
        BigDecimal confirmedReading = captured.value();
        BigDecimal effectiveConfirmedReading = confirmedReading;

        // A reading is compared only with earlier readings on its own channel: a kWh index is never a
        // flow meter's previous reading. A PDU reading is one run's duration rather than a running
        // total, so it has no earlier reading to compare with or to show back at all.
        boolean comparesWithEarlierReadings = resolvedChannel != ReadingChannel.PDU;
        Optional<TelemetryConfirmedReadingSnapshot> latestSnapshotOpt = comparesWithEarlierReadings
                ? telemetryTenantRepository.findLatestConfirmedReadingSnapshot(
                        schemaName, request.getSchemeId(), resolvedChannel, null)
                : Optional.empty();

        // For non-meter-replacement submissions, validate against the latest confirmed reading.
        // Meter-replacement submissions are treated as a new baseline.
        Optional<TelemetryConfirmedReadingSnapshot> validationBaselineOpt = isMeterReplaced
                ? latestSnapshotOpt
                : latestSnapshotOpt;

//        if (!isMeterReplaced
//                && validationBaselineOpt.isPresent()
//                && confirmedReading != null
//                && confirmedReading.compareTo(validationBaselineOpt.get().confirmedReading()) < 0) {
//            TelemetryConfirmedReadingSnapshot previousSnapshot = validationBaselineOpt.get();
//            String reason = "Submitted reading is less than previous confirmed reading.";
//            telemetryTenantRepository.createTenantAnomalyRecord(
//                    schemaName,
//                    operatorInRequest.id(),
//                    request.getSchemeId(),
//                    AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS,
//                    reason,
//                    AnomalyConstants.STATUS_OPEN
//            );
//            telemetryEventPublisher.publishAnomalyRecorded(
//                    tenantId,
//                    AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS,
//                    operatorInRequest.id(),
//                    request.getSchemeId(),
//                    extractedReading,
//                    confidenceLevel,
//                    confirmedReading,
//                    0,
//                    previousSnapshot.confirmedReading(),
//                    previousSnapshot.createdAt(),
//                    0,
//                    reason,
//                    AnomalyConstants.STATUS_OPEN,
//                    correlationId
//            );
//            return CreateReadingResponse.builder()
//                    .success(false)
//                    .message("Reading rejected because it is below the last confirmed reading. Submitted: "
//                            + toPlain(confirmedReading) + ". Last confirmed: " + toPlain(previousSnapshot.confirmedReading()) + ".")
//                    .correlationId(correlationId)
//                    .meterReading(confirmedReading)
//                    .qualityStatus("REJECTED")
//                    .lastConfirmedReading(previousSnapshot.confirmedReading())
//                    .build();
//        }

        Optional<WaterSupplyThreshold> thresholdOpt = !isMeterReplaced ? loadWaterSupplyThreshold(tenantId) : Optional.empty();
        Optional<BigDecimal> waterNormOpt = !isMeterReplaced ? loadWaterNorm(tenantId) : Optional.empty();
        BigDecimal minAllowed = null;
        if (thresholdOpt.isPresent() && waterNormOpt.isPresent()) {
            WaterSupplyThreshold threshold = thresholdOpt.get();
            minAllowed = waterNormOpt.get()
                    .multiply(BigDecimal.valueOf(100.0d - threshold.undersupplyThresholdPercent()))
                    .divide(BigDecimal.valueOf(100.0d), 6, RoundingMode.HALF_UP);
        }

//        if (!isMeterReplaced && effectiveConfirmedReading != null && minAllowed != null
//                && effectiveConfirmedReading.compareTo(minAllowed) < 0) {
//            TelemetryConfirmedReadingSnapshot previousSnapshot = validationBaselineOpt.orElse(null);
//            BigDecimal previousConfirmed = previousSnapshot != null ? previousSnapshot.confirmedReading() : null;
//            LocalDateTime previousConfirmedAt = previousSnapshot != null ? previousSnapshot.createdAt() : null;
//            String reason = "Submitted reading is below allowed minimum (" + toPlain(minAllowed) + ").";
//            telemetryTenantRepository.createTenantAnomalyRecord(
//                    schemaName,
//                    operatorInRequest.id(),
//                    request.getSchemeId(),
//                    AnomalyConstants.TYPE_LOW_WATER_SUPPLY,
//                    reason,
//                    AnomalyConstants.STATUS_OPEN
//            );
//            telemetryEventPublisher.publishAnomalyRecorded(
//                    tenantId,
//                    AnomalyConstants.TYPE_LOW_WATER_SUPPLY,
//                    operatorInRequest.id(),
//                    request.getSchemeId(),
//                    extractedReading,
//                    confidenceLevel,
//                    effectiveConfirmedReading,
//                    0,
//                    previousConfirmed,
//                    previousConfirmedAt,
//                    0,
//                    reason,
//                    AnomalyConstants.STATUS_OPEN,
//                    null
//            );
//            return CreateReadingResponse.builder()
//                    .success(false)
//                    .message("Reading rejected because it is below the allowed minimum. Submitted: "
//                            + toPlain(effectiveConfirmedReading) + ". Minimum allowed: " + toPlain(minAllowed) + ".")
//                    .correlationId(correlationId)
//                    .meterReading(effectiveConfirmedReading)
//                    .qualityStatus("REJECTED")
//                    .lastConfirmedReading(previousConfirmed)
//                    .build();
//        }

        // A duplicate *image* is one the OCR provider re-read to the previous confirmed value. An asserted
        // value carries no extraction, so ocrExtractedReading is null and the guard stays out of its way
        // — otherwise a genuine zero-consumption day resubmitted through the API was rejected as a
        // duplicate photo.
        if (latestSnapshotOpt.isPresent() && ocrExtractedReading != null
                && ocrExtractedReading.compareTo(latestSnapshotOpt.get().confirmedReading()) == 0
                && request.getReadingUrl() != null && !request.getReadingUrl().isBlank()) {
            TelemetryConfirmedReadingSnapshot previousSnapshot = latestSnapshotOpt.get();
            String anomalyCorrelationId = UUID.randomUUID().toString();
            recordAnomaly(
                    schemaName,
                    tenantId,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    AnomalyConstants.TYPE_DUPLICATE_IMAGE_SUBMISSION,
                    "Duplicate image submission detected. Extracted reading matches previous confirmed reading.",
                    0,
                    ocrExtractedReading,
                    confidenceLevel,
                    effectiveConfirmedReading,
                    previousSnapshot.confirmedReading(),
                    previousSnapshot.createdAt(),
                    0,
                    anomalyCorrelationId,
                    // ANOMALY-SUBMISSION-LINK: the duplicate is refused before createFlowReading, so
                    // this submission has no row. The *previous* reading it duplicates is not the
                    // submission that caused the anomaly and must not be linked as if it were.
                    null,
                    null
            );
            return CreateReadingResponse.builder()
                    .success(false)
                    .message("Duplicate image submission detected. The extracted reading matches the previous reading.")
                    .correlationId(responseCorrelationId)
                    .meterReading(confirmedReading)
                    .qualityConfidence(confidenceLevel)
                    .qualityStatus("REJECTED")
                    .errorCode(TelemetryErrorCode.DUPLICATE_IMAGE)
                    .lastConfirmedReading(previousSnapshot.confirmedReading())
                    .build();
        }

        // ── ROLLOVER-RESOLVE: resolve OCR rollover-digit ambiguity before the reading is confirmed.
        // The resolved value seeds confirmed_reading and is the number surfaced to the operator for
        // confirmation; extracted_reading stays the model value (dedup/audit). When the resolver is not
        // applicable (empty result) effectiveConfirmedReading is left untouched — byte-identical to legacy.
        // READING-PROVENANCE: an API-supplied value is not "as extracted" — nothing extracted it. The
        // capture step says how the value arrived, ahead of the rollover resolver, which cannot run on
        // that path (it needs an OCR result).
        int confirmedReadingSource = captured.source();
        String rolloverAuditJson = null;
        Optional<RolloverResolutionService.ResolvedReading> rollover =
                resolveRolloverIfApplicable(schemaName, request, resolvedChannel, ocrResult, isMeterReplaced, latestSnapshotOpt);
        if (rollover.isPresent()) {
            effectiveConfirmedReading = rollover.get().confirmedReading();
            confirmedReadingSource = rollover.get().source();
            rolloverAuditJson = rollover.get().auditJson();
        }

        // ── SUPPLY-PLAUSIBILITY: decide before the row is written, not after.
        // Placed after rollover resolution so the value assessed is effectiveConfirmedReading — the
        // number that will actually be stored — and before persistence so the publish/no-publish
        // decision is held in memory rather than depending on a second write succeeding.
        //
        // Only BFM readings are cumulative m3 indices, so the delta is meaningless on any other channel.
        //
        // A pre-V40 tenant is skipped entirely rather than checked: storing a quarantined row it has
        // no column to mark would leave it indistinguishable from an accepted one, which is worse
        // than not checking at all.
        boolean supplyCheckApplies = request.isSupplyPlausibilityChecked()
                && !supplyPlausibilityGuard.isDisabled()
                && !isMeterReplaced
                && resolvedChannel == ReadingChannel.BFM
                && telemetryTenantRepository.supportsQuarantine(schemaName);

        TelemetryConfirmedReadingSnapshot supplyBaseline = null;
        boolean quarantined = false;
        if (supplyCheckApplies) {
            // Deliberately re-fetched rather than reusing latestSnapshotOpt: this is the same
            // "latest non-quarantined confirmed reading strictly before this date" that analytics
            // computes its volume from, so the litres judged here and the litres the warehouse
            // would have stored are the same number by construction. One extra query on this path.
            supplyBaseline = telemetryTenantRepository.findLatestConfirmedReadingSnapshotBeforeDate(
                    schemaName,
                    request.getSchemeId(),
                    resolvedChannel,
                    LocalDate.from(readingAt),
                    null).orElse(null);
            Verdict verdict = supplyPlausibilityGuard.assess(
                    schemaName,
                    tenantId,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    LocalDate.from(readingAt),
                    effectiveConfirmedReading,
                    supplyBaseline != null ? supplyBaseline.confirmedReading() : null,
                    SupplyPlausibilityGuard.Path.SUBMISSION);
            // Under AUDIT the guard has already logged and counted what it would have done; the
            // reading proceeds untouched from here.
            quarantined = verdict instanceof Verdict.Quarantined && supplyPlausibilityGuard.isEnforcing();
        }

        // Written by the insert itself rather than by a later UPDATE, so no stored reading is ever
        // without its channel. The value is in the channel's standard unit; submitted_unit records the
        // unit it arrived in.
        String submittedUnit = captured.submittedUnitCode();
        // Fixed here because a PDU run is written through a callback, inside its day's lock.
        BigDecimal valueToStore = effectiveConfirmedReading;
        Integer assertedSource = request.isExternallyAsserted() ? confirmedReadingSource : null;
        boolean storeQuarantined = quarantined;
        Supplier<FlowReadingVersion> store = () -> {
            Optional<Long> placeholderIdOpt = telemetryTenantRepository.findLatestPlaceholderFlowReadingIdForDate(
                    schemaName,
                    request.getSchemeId(),
                    operatorInRequest.id(),
                    LocalDate.from(readingAt)
            );
            if (lenientIngestion || request.isExternallyAsserted() || storeQuarantined) {
                // LENIENT-INGEST: persist the reading and its ingestion tracking (source + submitted scheme
                // ids / phone hash) atomically, so a failure can never leave a recorded reading without its
                // tracking metadata. Covers both the new-insert and same-day placeholder-reuse paths.
                // READING-PROVENANCE: API-supplied values take the same transactional path — same inserts and
                // updates as before, plus the EXTERNALLY_ASSERTED marker committed with the row.
                return telemetryTenantRepository.persistFlowReadingWithTracking(
                        schemaName,
                        placeholderIdOpt.orElse(null),
                        request.getSchemeId(),
                        operatorInRequest.id(),
                        readingAt,
                        extractedReading,
                        valueToStore,
                        storageCorrelationId,
                        ocrCorrelationId,
                        request.getReadingUrl(),
                        request.getMeterChangeReason(),
                        request.getIngestionSource() != null ? request.getIngestionSource() : IngestionSource.NORMAL,
                        request.getSubmittedStateSchemeId(),
                        request.getSubmittedCentreSchemeId(),
                        request.getSubmittedPhoneHash(),
                        assertedSource,
                        // SUPPLY-PLAUSIBILITY: the marker commits inside the same transaction as the
                        // insert, so the row cannot land without it.
                        storeQuarantined ? QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY : null,
                        resolvedChannel,
                        submittedUnit,
                        request.getReportedVia());
            }
            if (placeholderIdOpt.isPresent()) {
                return telemetryTenantRepository.updateFlowReadingFromIngestion(
                        schemaName,
                        placeholderIdOpt.get(),
                        readingAt,
                        extractedReading,
                        valueToStore,
                        storageCorrelationId,
                        ocrCorrelationId,
                        request.getReadingUrl(),
                        request.getMeterChangeReason(),
                        operatorInRequest.id(),
                        resolvedChannel,
                        submittedUnit,
                        request.getReportedVia()
                );
            }
            return telemetryTenantRepository.createFlowReading(
                    schemaName,
                    request.getSchemeId(),
                    operatorInRequest.id(),
                    readingAt,
                    extractedReading,
                    valueToStore,
                    storageCorrelationId,
                    ocrCorrelationId,
                    request.getReadingUrl(),
                    request.getMeterChangeReason(),
                    resolvedChannel,
                    submittedUnit,
                    request.getReportedVia()
            );
        };
        FlowReadingVersion storedReading;
        if (resolvedChannel == ReadingChannel.PDU) {
            // A new run: whichever row it lands on, a placeholder or a new one, holds no minutes yet.
            Optional<FlowReadingVersion> stored = pduDayLimit.writeWithinLimit(
                    schemaName, request.getSchemeId(), LocalDate.from(readingAt), valueToStore, () -> null, store);
            if (stored.isEmpty()) {
                return rejected(PduDayLimit.EXCEEDED.errorCode(), PduDayLimit.EXCEEDED.message());
            }
            storedReading = stored.get();
        } else {
            storedReading = store.get();
        }
        Long readingId = storedReading.id();

        // ROLLOVER-RESOLVE: tag provenance + best-effort audit only when the resolver actually overrode
        // the model value. createReading is not @Transactional, so this runs as a separate guarded
        // statement (audit failure only warns) — acceptable, provenance is audit-only. Every other row
        // keeps the column's DEFAULT 0. (API-supplied values never reach here: their marker is written
        // inside the insert transaction above.)
        if (confirmedReadingSource == RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED) {
            telemetryTenantRepository.applyConfirmedReadingSource(
                    schemaName, readingId, confirmedReadingSource, rolloverAuditJson);
        }

        // LOCATION-AFFINITY: coordinates the request carried belong on the reading row, not only in
        // the anomaly. Only the state-IT paths supply them here — the chatbot paths write them onto the
        // placeholder row from /location and leave the request null — and without this an
        // API-submitted mismatch could not be re-measured from the stored reading alone, which is what
        // the anomaly's own distance disclosure promises. Best-effort by design: createReading is not
        // @Transactional at this point and the row is already stored, so failing to annotate it must
        // not lose a recorded reading. Runs before the quarantine block below, which returns early.
        if (request.getLatitude() != null && request.getLongitude() != null) {
            try {
                telemetryTenantRepository.updateReadingLocation(
                        schemaName,
                        readingId,
                        request.getLatitude(),
                        request.getLongitude(),
                        operatorInRequest.id());
            } catch (Exception e) {
                log.warn("reading_location_persist_failed readingId={}: {}", readingId, e.getMessage());
            }
        }

        // LOCATION-AFFINITY: the row now exists, so this is the point at which "the anomaly is
        // captured against this submission" becomes possible. Deliberately *before* the quarantine
        // block below, which returns early: a reading can be both implausibly high and taken from
        // the wrong place, and suppressing one because of the other would lose a real signal.
        //
        // This is also where the WhatsApp operator's "Yes" is observed. The flow never tells the
        // backend the answer — a confirmation is this method being reached at all, and a decline
        // leaves only the placeholder row /location already wrote.
        //
        // Coordinates on the request come only from the state-IT APIs (same scoping as
        // supplyPlausibilityChecked); the chatbot paths leave them null and the service reads them
        // back off the reused placeholder row. That origin is what the metric's path tag records,
        // so if a future caller starts supplying coordinates the tag follows it.
        if (locationAffinityService != null) {
            locationAffinityService.recordMismatchIfAny(
                    schemaName,
                    tenantId,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    new ReadingSubmission(readingId, storageCorrelationId, LocalDate.from(readingAt)),
                    request.getLatitude(),
                    request.getLongitude(),
                    request.getLatitude() != null
                            ? LocationAffinityService.Path.STATE_API
                            : LocationAffinityService.Path.IMAGE_SUBMISSION);
        }

        // SUPPLY-PLAUSIBILITY: the row is stored and marked, but it is not a reading. The channel
        // still belongs on it, and the anomaly is still published — "nothing reaches analytics" was
        // never literal. What is withheld is publishMeterReadingRecorded, the single event that
        // writes fact_meter_reading, fact_operator_attendance and fact_water_quantity. Withholding it
        // marks the operator absent and the scheme non-reporting for the day: intended, and called
        // out in the ops runbook so the daily-report gap is not chased as a pipeline fault.
        if (quarantined) {
            recordAnomaly(
                    schemaName,
                    tenantId,
                    operatorInRequest.id(),
                    request.getSchemeId(),
                    AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY,
                    AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_SUBMITTED,
                    0,
                    ocrExtractedReading,
                    confidenceLevel,
                    effectiveConfirmedReading,
                    // The baseline the litres were measured against, not the standing stored value:
                    // (overridden_reading - previous_reading) * 1000 must reproduce the decision
                    // from the persisted row alone.
                    supplyBaseline != null ? supplyBaseline.confirmedReading() : null,
                    supplyBaseline != null ? supplyBaseline.createdAt() : null,
                    0,
                    buildSupplyAnomalyCorrelationId(
                            AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY,
                            operatorInRequest.id(),
                            request.getSchemeId(),
                            LocalDate.from(readingAt)),
                    // ANOMALY-SUBMISSION-LINK: the quarantined row IS the submission that caused
                    // this. Both were already in hand here and were previously dropped, which is
                    // what left the anomaly matched to its reading only by operator, scheme and day.
                    readingId,
                    storageCorrelationId);
            return CreateReadingResponse.builder()
                    .success(false)
                    // THRESHOLD-DISCLOSURE: names no ceiling, population or FHTC figure. Echoing
                    // "maximum allowed: N" would let any API-key holder solve for the scheme's
                    // connection count and the per-person limit in two submissions.
                    .message(messageOverride(contactId,
                            "Reading rejected: this reading looks unusually high for this scheme. "
                                    + "Please check the meter reading and try again."))
                    .correlationId(responseCorrelationId)
                    .meterReading(effectiveConfirmedReading)
                    .qualityConfidence(confidenceLevel)
                    .qualityStatus("REJECTED")
                    .errorCode(TelemetryErrorCode.ABNORMAL_READING)
                    // The caller's own previous reading, which every successful submission already
                    // returns. It discloses nothing they did not submit themselves.
                    .lastConfirmedReading(supplyBaseline != null ? supplyBaseline.confirmedReading() : null)
                    .build();
        }

        BigDecimal lastConfirmedReading = latestSnapshotOpt
                .map(TelemetryConfirmedReadingSnapshot::confirmedReading)
                .orElse(null);
        if (lastConfirmedReading == null && comparesWithEarlierReadings) {
            lastConfirmedReading = telemetryTenantRepository
                    .findLastConfirmedReading(schemaName, request.getSchemeId(), resolvedChannel, readingId)
                    .orElse(null);
        }

        telemetryEventPublisher.publishMeterReadingRecorded(
                tenantId,
                request.getSchemeId(),
                operatorInRequest.id(),
                // Null, not the persisted 0: analytics buckets a submission as compliant when
                // extracted_reading == confirmed_reading and anomalous when they differ, and both
                // filters skip NULL. Publishing 0 would file every asserted reading as an operator
                // overriding the AI.
                ocrExtractedReading,
                effectiveConfirmedReading,
                confidenceLevel,
                request.getReadingUrl(),
                readingAt,
                channel,
                LocalDate.from(readingAt),
                1,
                0,
                // ANOMALY-SUBMISSION-LINK: the same value written to flow_reading_table.correlation_id
                // above, so the warehouse row can be found from an anomaly that names it.
                storageCorrelationId,
                readingId,
                // The version the write above gave the row. The markers written after it move
                // updated_at on without changing anything published here, and a later republish
                // reads the newer value, so analytics still keeps the latest.
                storedReading.updatedAt(),
                calculationParametersSnapshotter.snapshot(schemaName, tenantId, request.getSchemeId(), resolvedChannel)
        );

        // Surface the resolved value to the operator: the "please confirm" message text and the response
        // meterReading must show the resolved number, not the model pick. extractedReading (persisted, and
        // the dedup key) stays the model value — only the displayed/confirmed number changes.
        if (confirmedReadingSource == RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED) {
            finalReading = effectiveConfirmedReading;
            // The resolved value now drives the response, so re-derive validity from it (confidence is
            // unchanged): a positive resolved reading must yield a successful "captured" response even if
            // the model's own pick was non-positive.
            hasPositiveReading = finalReading != null && finalReading.compareTo(BigDecimal.ZERO) > 0;
            isValid = hasPositiveReading && hasAcceptableConfidence;
        }

        String finalMessage;
        String readingText = finalReading != null ? finalReading.stripTrailingZeros().toPlainString() : null;
        if (isValid) {
            if (ocrResult != null && readingText != null) {
                finalMessage = "Reading captured successfully. Extracted reading: " + readingText;
            } else {
                finalMessage = "Reading captured successfully";
            }
            if (lastConfirmedReading != null) {
                finalMessage = finalMessage + " Your last confirmed reading was "
                        + lastConfirmedReading.stripTrailingZeros().toPlainString() + ".";
            }
        } else if (!hasPositiveReading) {
            finalMessage = "Invalid reading value";
        } else if (ocrResult != null && readingText != null) {
            finalMessage = "Low OCR confidence. Extracted reading: " + readingText + ". Please confirm reading.";
        } else {
            finalMessage = "Low OCR confidence. Please confirm reading.";
        }

        return CreateReadingResponse.builder()
                .success(hasPositiveReading)
                .message(messageOverride(contactId, finalMessage))
                .correlationId(responseCorrelationId)
                .meterReading(finalReading)
                .qualityConfidence(confidenceLevel)
                .qualityStatus(ocrResult != null ? ocrResult.getQualityStatus() : (isValid ? "CONFIRMED" : "REVIEW"))
                .lastConfirmedReading(lastConfirmedReading)
                .build();
    }

    /**
     * Runs the rollover resolver when — and only when — it can act, returning its result or
     * {@link Optional#empty()} to signal "leave confirmed_reading exactly as the caller had it".
     *
     * <p>The gate is kept tight for chatbot-timeout hygiene: the overwhelming majority of readings have no
     * rollover, so the common path must add <em>zero</em> extra DB round-trips — the trailing-history fetch
     * happens only after every cheap in-memory check passes (never eagerly, relying on an in-{@code resolve}
     * short-circuit that runs after the query). The gate also requires the tenant schema to be migrated with
     * {@code confirmed_reading_source} (V35): on a pre-migration tenant the provenance could not be recorded,
     * so overriding confirmed_reading would be indistinguishable from an unmodified row — we skip instead,
     * keeping behaviour byte-identical to legacy. On the manual/confirmed path {@code ocrResult} is null, so
     * the gate is false and the caller's value is untouched.
     */
    private Optional<RolloverResolutionService.ResolvedReading> resolveRolloverIfApplicable(
            String schemaName,
            CreateReadingRequest request,
            ReadingChannel channel,
            OcrReadingResult ocrResult,
            boolean isMeterReplaced,
            Optional<TelemetryConfirmedReadingSnapshot> latestSnapshotOpt) {
        if (!rolloverResolutionService.isEnabled()
                || ocrResult == null
                || !ocrResult.isHasRollover()
                || ocrResult.getRolloverPositions() == null
                || ocrResult.getRolloverPositions().isEmpty()
                || isMeterReplaced
                || latestSnapshotOpt.isEmpty()
                || !telemetryTenantRepository.supportsConfirmedReadingSource(schemaName)) {
            return Optional.empty();
        }
        List<DailyConfirmedReading> dailyHistory = telemetryTenantRepository
                .findRecentDailyConfirmedReadings(schemaName, request.getSchemeId(), channel, null, ROLLOVER_HISTORY_DAYS);
        return Optional.of(rolloverResolutionService.resolve(
                ocrResult,
                dailyHistory,
                latestSnapshotOpt.get().confirmedReading(),
                isMeterReplaced));
    }

    /** A submission refused before anything is stored, so it has no correlation id of its own yet. */
    private static CreateReadingResponse rejected(TelemetryErrorCode errorCode, String message) {
        return CreateReadingResponse.builder()
                .success(false)
                .message(message)
                .correlationId(UUID.randomUUID().toString())
                .qualityStatus("REJECTED")
                .errorCode(errorCode)
                .build();
    }

    /**
     * A value sent with the submission is captured as it is, even when a photo came with it: the photo
     * is kept on the row and OCR doesn't run. Only a submission with neither is refused here.
     */
    private ReadingCapture captureFor(CreateReadingRequest request) {
        if (request.getReadingValue() != null) {
            return submittedValueCapture;
        }
        if (request.getReadingUrl() == null || request.getReadingUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either readingValue or readingUrl must be provided");
        }
        return imageReadingCapture;
    }

    @Transactional
    public CreateReadingResponse updateConfirmedReading(String correlationId, BigDecimal confirmedReading) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "correlationId must be provided");
        }
        return updateConfirmedReadingByCorrelationId(correlationId, confirmedReading, null, null);
    }

    @Transactional
    public CreateReadingResponse updateConfirmedReading(String correlationId, String phoneNumber, BigDecimal confirmedReading) {
        return updateConfirmedReading(correlationId, phoneNumber, confirmedReading, null);
    }

    /**
     * PHONE-OPTIONAL: a correction only needs one way to find the row it corrects — either the
     * correlationId of the original submission or the submitter's phone (whose latest reading is
     * corrected). Either alone is sufficient; correlationId wins when both are present.
     *
     * <p>{@code tenantId} is the tenant the caller authenticated as (API key). It is used on the
     * correlationId path only, where there is no operator to derive a tenant from: it resolves the
     * tenant schema and backstops the tenant on the published event. {@code null} is accepted for
     * callers that have no authenticated tenant, which then fall back to {@link TenantContext}.
     */
    @Transactional
    public CreateReadingResponse updateConfirmedReading(String correlationId,
                                                        String phoneNumber,
                                                        BigDecimal confirmedReading,
                                                        Integer tenantId) {
        return updateConfirmedReading(correlationId, phoneNumber, confirmedReading, null, tenantId);
    }

    /**
     * As {@link #updateConfirmedReading(String, String, BigDecimal, Integer)}, with the unit
     * {@code confirmedReading} is given in. The unit is checked against the corrected row's channel and
     * the value converted to that channel's standard unit, as on a submission.
     *
     * @param readingUnit the declared unit, unchecked; null or blank means the channel's standard unit
     */
    @Transactional
    public CreateReadingResponse updateConfirmedReading(String correlationId,
                                                        String phoneNumber,
                                                        BigDecimal confirmedReading,
                                                        String readingUnit,
                                                        Integer tenantId) {
        if (confirmedReading == null || confirmedReading.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmedReading must be a non-negative number");
        }

        if (correlationId != null && !correlationId.isBlank()) {
            return updateConfirmedReadingByCorrelationId(correlationId.trim(), confirmedReading, readingUnit, tenantId);
        }

        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Either correlationId or phoneNumber must be provided"
            );
        }

        // Same cross-tenant exposure as the reset path: the phone lookup spans every tenant schema, so
        // an authenticated tenant has to bound which operator's reading this correction may overwrite.
        // tenantId is null only for the in-process overloads that have no authenticated caller.
        TelemetryOperatorWithSchema operatorWithSchema = tenantId != null
                ? resolveOperatorInTenant(phoneNumber, tenantId)
                : operatorContextService.resolveOperatorWithSchema(phoneNumber);
        String schemaName = operatorWithSchema.schemaName();
        TelemetryOperator operator = operatorWithSchema.operator();

        TelemetryLatestFlowReadingRecord latestReading = telemetryTenantRepository
                .findLatestFlowReadingByOperator(schemaName, operator.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, OPERATOR_LOOKUP_MISS));

        return applyConfirmedReadingCorrection(
                schemaName, latestReading, confirmedReading, readingUnit, operator.id(), operator.tenantId());
    }

    private CreateReadingResponse updateConfirmedReadingByCorrelationId(String correlationId,
                                                                        BigDecimal confirmedReading,
                                                                        String readingUnit,
                                                                        Integer tenantId) {
        String schemaName = resolveSchemaForCorrelationUpdate(tenantId);

        if (confirmedReading == null || confirmedReading.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmedReading must be a non-negative number");
        }

        TelemetryLatestFlowReadingRecord reading = telemetryTenantRepository
                .findFlowReadingDetailsByCorrelationId(schemaName, correlationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reading not found"));

        Integer eventTenantId = null;
        if (reading.createdBy() != null) {
            eventTenantId = telemetryTenantRepository.findOperatorById(schemaName, reading.createdBy())
                    .map(TelemetryOperator::tenantId)
                    .orElse(null);
        }
        // analytics-service drops the operator-attendance and water-quantity facts for any event with a
        // null tenantId, so fall back to the tenant the caller authenticated as rather than publishing a
        // correction that silently never reaches the dashboards. Resolved before the write rather than
        // after it because the plausibility check needs the tenant to read its household-size config;
        // the lookup is read-only, so hoisting it changes nothing else.
        if (eventTenantId == null) {
            eventTenantId = tenantId;
        }

        return applyConfirmedReadingCorrection(
                schemaName,
                reading,
                confirmedReading,
                readingUnit,
                reading.createdBy() != null ? reading.createdBy() : 1L,
                eventTenantId);
    }

    /**
     * PHONE-OPTIONAL: the correlationId path has no operator to derive a schema from, so the tenant has
     * to come from the request itself. The API-key tenant is preferred over the {@code X-Tenant-Code}
     * header behind {@link TenantContext}: that header is unauthenticated, so letting it win would let a
     * caller holding one tenant's API key reach another tenant's schema. The header stays as the
     * fallback for callers that have no authenticated tenant (the correlationId-only overload).
     */
    private String resolveSchemaForCorrelationUpdate(Integer tenantId) {
        if (tenantId != null) {
            String apiKeySchema = telemetryTenantRepository.findSchemaNameByTenantId(tenantId).orElse(null);
            if (apiKeySchema != null && !apiKeySchema.isBlank()) {
                return apiKeySchema;
            }
        }
        String contextSchema = TenantContext.getSchema();
        if (contextSchema != null && !contextSchema.isBlank()) {
            return contextSchema;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant could not be resolved");
    }

    /**
     * The body both correction routes share once they have resolved the row to correct: apply the
     * submission rules of the row's channel (unit, PDU limits) and the supply-plausibility rule, then
     * either write the value or refuse it.
     *
     * <p>SUPPLY-PLAUSIBILITY, §6.3. <strong>A failing correction never writes
     * {@code confirmed_reading}</strong>; a passing one writes it and clears the quarantine flag.
     * Neither decision looks at the target row's own quarantine state — {@code quarantineReason}
     * selects the anomaly reason text and nothing else.
     *
     * <p>That is not fastidiousness, it is the whole point. Storing a refused value <em>and</em>
     * quarantining it would drop the row out of telemetry's baselines while analytics kept the older
     * published figure, so the next day's delta would be measured from a baseline two readings back
     * and land far above the ceiling — one bad correction would quarantine the scheme indefinitely,
     * the mirror image of the divergence this feature exists to prevent. Leaving the row alone keeps
     * both stores agreeing in every case: a published target keeps its published value, a
     * quarantined target keeps being excluded on both sides.
     *
     * <p>The cost, stated plainly: when the target was published, analytics keeps a value the
     * operator has just called wrong. There is no better candidate, because the proposed replacement
     * is the one we have declared impossible. If the value really is right, the fix is the FHTC
     * master data or the threshold, not forcing the number through.
     *
     * <p>Unlike the submission path there is no opt-in flag: {@code createReading} is shared with the
     * chatbot image workflow and so needs one, whereas this method is reached only from
     * {@code PUT /readings} — the chatbot confirm path updates the repository directly.
     *
     * @param submittedReading the corrected value, in {@code readingUnit}
     * @param readingUnit      the unit the caller declared, unchecked; null or blank means the
     *                         channel's standard unit
     * @param updatedBy        the operator credited with the correction, and the operator the anomaly
     *                         is filed against
     * @param eventTenantId    the tenant for the published event and for the household-size config
     */
    private CreateReadingResponse applyConfirmedReadingCorrection(String schemaName,
                                                                  TelemetryLatestFlowReadingRecord reading,
                                                                  BigDecimal submittedReading,
                                                                  String readingUnit,
                                                                  Long updatedBy,
                                                                  Integer eventTenantId) {
        LocalDate readingDate = readingDateOf(reading);
        // An absent channel reads as BFM, which is what analytics assumes for the same rows.
        ReadingChannel channel = ReadingChannel.fromCode(reading.channel());

        // A correction can't store what the same channel's submission would have been refused. A
        // refused unit, PDU run or PDU day writes nothing, and no anomaly: it is the request that is
        // wrong, not the reading.
        CapturedReading captured;
        switch (submittedValueCapture.captureCorrection(channel, submittedReading, readingUnit)) {
            case CaptureOutcome.Captured(CapturedReading correction) -> captured = correction;
            case CaptureOutcome.Rejected(TelemetryErrorCode errorCode, String rejection) -> {
                return rejectedCorrection(reading, errorCode, rejection);
            }
            case CaptureOutcome.Retry retry ->
                    throw new IllegalStateException("A submitted value is never retried");
        }
        // In the channel's standard unit, so the checks below and the stored value agree.
        BigDecimal confirmedReading = captured.value();

        // A pre-V40 tenant is skipped rather than checked, as on the submission path: refusing a
        // correction on a schema that cannot record a quarantine leaves the two stores' notion of
        // this row's status unexpressible. Only BFM rows are cumulative m3 indices, so the delta is
        // not a water volume on any other channel.
        boolean supplyCheckApplies = !supplyPlausibilityGuard.isDisabled()
                && channel == ReadingChannel.BFM
                && telemetryTenantRepository.supportsQuarantine(schemaName);

        if (supplyCheckApplies) {
            // excludeReadingId exists for exactly this: the row being corrected must not be its own
            // baseline. Otherwise the correction would be measured against the value it replaces and
            // every correction would look like a tiny delta.
            TelemetryConfirmedReadingSnapshot baseline = telemetryTenantRepository
                    .findLatestConfirmedReadingSnapshotBeforeDate(
                            schemaName, reading.schemeId(), channel, readingDate, reading.id())
                    .orElse(null);
            Verdict verdict = supplyPlausibilityGuard.assess(
                    schemaName,
                    eventTenantId,
                    updatedBy,
                    reading.schemeId(),
                    readingDate,
                    confirmedReading,
                    baseline != null ? baseline.confirmedReading() : null,
                    SupplyPlausibilityGuard.Path.CORRECTION);
            // Under AUDIT the guard has already logged and counted what it would have refused; the
            // correction proceeds untouched from here.
            if (verdict instanceof Verdict.Quarantined && supplyPlausibilityGuard.isEnforcing()) {
                return refuseCorrection(schemaName, reading, confirmedReading, updatedBy, eventTenantId, baseline);
            }
        }

        Supplier<Long> write = () -> {
            telemetryTenantRepository.updateConfirmedReading(
                    schemaName,
                    reading.id(),
                    confirmedReading,
                    updatedBy,
                    RolloverResolutionService.manualConfirmSource(confirmedReading, reading.confirmedReading()),
                    captured.submittedUnitCode(),
                    // Reached only from PUT /readings (see above).
                    ReportingChannel.API
            );
            return reading.id();
        };
        if (channel == ReadingChannel.PDU) {
            // The corrected row's old minutes don't count towards its day.
            if (pduDayLimit.writeWithinLimit(
                    schemaName, reading.schemeId(), readingDate, confirmedReading, reading::id, write).isEmpty()) {
                return rejectedCorrection(reading, PduDayLimit.EXCEEDED.errorCode(), PduDayLimit.EXCEEDED.message());
            }
        } else {
            write.get();
        }
        // SUPPLY-PLAUSIBILITY: the release path. Unconditional, and deliberately outside the check's
        // own branch — a clean row is set to the 0 it already holds, and a quarantined row is
        // published below for the first time. Clearing here rather than only when the check ran is
        // what keeps mode=OFF a real kill switch: a row quarantined during an earlier ENFORCE window
        // would otherwise sit outside every baseline with no route back. A no-op on pre-V40 schemas,
        // where the repository guards on the column existing.
        telemetryTenantRepository.applyQuarantineReason(schemaName, reading.id(), QuarantineReason.NONE);

        readingRepublisher.republish(schemaName, eventTenantId, reading.id());

        return CreateReadingResponse.builder()
                .success(true)
                .message("Reading updated successfully")
                .correlationId(reading.correlationId())
                .meterReading(confirmedReading)
                .qualityStatus("CONFIRMED")
                .build();
    }

    /** A correction refused for breaking its channel's submission rules: nothing is written. */
    private static CreateReadingResponse rejectedCorrection(TelemetryLatestFlowReadingRecord reading,
                                                            TelemetryErrorCode errorCode,
                                                            String message) {
        return CreateReadingResponse.builder()
                .success(false)
                .message(message)
                .correlationId(reading.correlationId())
                .qualityStatus("REJECTED")
                .errorCode(errorCode)
                .build();
    }

    /**
     * SUPPLY-PLAUSIBILITY: records a refused correction and returns the rejection. Writes nothing to
     * {@code flow_reading_table} — not the value, and not a marker: the row is untouched by
     * definition, so {@code quarantine_reason} keeps whatever it held. The anomaly's reason text is
     * therefore the only thing telling a refusal over a published reading (B) from one over a
     * quarantined reading (C), which is why {@link #anomalyReasonForRefusedCorrection} reads the
     * row's state and the write path above does not.
     */
    private CreateReadingResponse refuseCorrection(String schemaName,
                                                   TelemetryLatestFlowReadingRecord reading,
                                                   BigDecimal attemptedReading,
                                                   Long updatedBy,
                                                   Integer eventTenantId,
                                                   TelemetryConfirmedReadingSnapshot baseline) {
        recordAnomaly(
                schemaName,
                eventTenantId,
                updatedBy,
                reading.schemeId(),
                AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY,
                anomalyReasonForRefusedCorrection(reading),
                0,
                // No OCR pass on a correction: the caller supplied the number directly, so there is
                // no AI reading to file the attempt against.
                null,
                null,
                attemptedReading,
                // The baseline the litres were measured against, not the standing stored value, so
                // (overridden_reading - previous_reading) * 1000 reproduces the decision from the
                // persisted row alone. The standing value is not lost either: the reading row is
                // untouched, so confirmed_reading still holds it.
                baseline != null ? baseline.confirmedReading() : null,
                baseline != null ? baseline.createdAt() : null,
                0,
                // Deliberately null, so the publisher mints a random uuid per attempt. Analytics
                // dedups on a uuid derived from the correlationId and touches rather than inserts a
                // repeat, so a deterministic key would collapse a second refused attempt into the
                // first and the operator's repeated tries would be invisible. The submission path
                // wants the opposite and keys on (type, operator, scheme, date).
                null,
                // ANOMALY-SUBMISSION-LINK: the reading being corrected. The refusal writes nothing
                // to flow_reading_table, so this points at the standing row the correction targeted
                // — which is the submission this anomaly is about, whether it is published (case B)
                // or itself quarantined (case C).
                reading.id(),
                reading.correlationId());

        return CreateReadingResponse.builder()
                .success(false)
                // THRESHOLD-DISCLOSURE: names no ceiling, population or FHTC figure, for the same
                // reason the submission rejection does not.
                .message("Correction rejected: this reading looks unusually high for this scheme. "
                        + "Please check the meter reading and try again.")
                .correlationId(reading.correlationId())
                .meterReading(attemptedReading)
                .qualityStatus("REJECTED")
                .errorCode(TelemetryErrorCode.ABNORMAL_READING)
                // The value that still stands. It is the caller's own stored reading, so it
                // discloses nothing they could not already read back.
                .lastConfirmedReading(reading.confirmedReading())
                .build();
    }

    /**
     * Case B or case C of the refusal (§6.3), told apart by whether the reading the correction
     * targeted is itself quarantined. They mean different things to whoever picks the anomaly up: in
     * B a published value stands and the day is counted, in C the day is still missing from analytics
     * and needs a plausible value before it will ever appear.
     *
     * <p>{@code null} means a pre-V40 schema, where the check never runs at all; it reads as
     * published for completeness.
     */
    private static String anomalyReasonForRefusedCorrection(TelemetryLatestFlowReadingRecord reading) {
        Integer quarantineReason = reading.quarantineReason();
        boolean targetQuarantined = quarantineReason != null && quarantineReason != QuarantineReason.NONE;
        return targetQuarantined
                ? AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_QUARANTINED
                : AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_PUBLISHED;
    }

    /**
     * The date a stored reading belongs to, falling back through {@code reading_at} to today for the
     * legacy rows that carry neither. Shared so the baseline cutoff and the published event agree on
     * which day is being corrected.
     */
    private static LocalDate readingDateOf(TelemetryLatestFlowReadingRecord reading) {
        if (reading.readingDate() != null) {
            return reading.readingDate();
        }
        return (reading.readingAt() != null ? reading.readingAt() : ReadingTime.now()).toLocalDate();
    }

    /**
     * Zeroes the operator's latest confirmed reading, scoped to the tenant the caller authenticated as.
     *
     * <p>This is the most destructive route in the service: it overwrites the only copy of a confirmed
     * reading, and it addresses it by phone number, which is guessable. Two things therefore bound it.
     * {@code tenantId} is mandatory — a caller with no authenticated tenant gets a 401 rather than an
     * unscoped search — and the operator the phone resolves to must belong to that tenant. The second
     * check is not redundant: {@code resolveOperatorWithSchema} <em>prefers</em> the given tenant but
     * falls back to a match in any other schema, so without it a valid tenant-A key would reach a
     * tenant-B operator's reading.
     *
     * <p>A cross-tenant hit and an unknown contact answer identically (404, same reason) so the
     * endpoint cannot be used to test whether a phone number is registered in some other tenant. The
     * previous value is carried back on {@code lastConfirmedReading} so the caller and the audit log
     * both retain what the reset destroyed.
     *
     * @param reportedVia the channel the reset came through, recorded on the row as the writer of its 0
     */
    @Transactional
    public CreateReadingResponse resetLatestConfirmedReadingByPhone(String phoneNumber,
                                                                    Integer tenantId,
                                                                    ReportingChannel reportedVia) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phoneNumber must be provided");
        }
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key");
        }

        TelemetryOperatorWithSchema operatorWithSchema = resolveOperatorInTenant(phoneNumber, tenantId);
        String schemaName = operatorWithSchema.schemaName();
        TelemetryOperator operator = operatorWithSchema.operator();

        TelemetryLatestFlowReadingRecord latestReading = telemetryTenantRepository
                .findLatestFlowReadingByOperator(schemaName, operator.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, OPERATOR_LOOKUP_MISS));

        // The reset has no unit field, so its 0 is in the standard unit of the row's channel, as on
        // every correction path.
        telemetryTenantRepository.updateConfirmedReading(
                schemaName,
                latestReading.id(),
                BigDecimal.ZERO,
                operator.id(),
                null,
                ReadingChannel.fromCode(latestReading.channel()).standardUnit()
                        .map(ReadingUnit::code)
                        .orElse(null),
                reportedVia
        );
        // SUPPLY-PLAUSIBILITY: the marker described the value the reset has just destroyed, so it
        // cannot outlive it — 0 is not an implausible supply. Leaving it behind is not cosmetic:
        // a quarantined row that is reset now satisfies every clause of the placeholder predicate
        // (zero extracted and confirmed readings, no image, no meter-change or issue reason) for an
        // API submission that carried no image, so the next submission that day reuses the row. The
        // reuse paths pass no quarantine reason, meaning "leave the column alone", and a stale 1
        // would then silently withhold a perfectly good reading from every baseline and from the
        // warehouse. Same unconditional clear as the correction path, and the same no-op on pre-V40.
        telemetryTenantRepository.applyQuarantineReason(schemaName, latestReading.id(), QuarantineReason.NONE);

        // Published from the row as it now stands. Its created_by is operator.id(), because the row
        // is the operator's own latest, so the event is credited as it was before.
        readingRepublisher.republish(schemaName, operator.tenantId(), latestReading.id());

        return CreateReadingResponse.builder()
                .success(true)
                .message("Latest confirmed reading reset to 0")
                .correlationId(latestReading.correlationId())
                .meterReading(BigDecimal.ZERO)
                .lastConfirmedReading(latestReading.confirmedReading())
                .qualityStatus("CONFIRMED")
                .build();
    }

    /**
     * Resolves an operator by phone and refuses anything outside {@code tenantId}. Both a miss and a
     * cross-tenant hit surface as the same 404, so neither confirms that the contact exists elsewhere.
     */
    private TelemetryOperatorWithSchema resolveOperatorInTenant(String phoneNumber, Integer tenantId) {
        TelemetryOperatorWithSchema operatorWithSchema;
        try {
            operatorWithSchema = operatorContextService.resolveOperatorWithSchema(phoneNumber, tenantId);
        } catch (IllegalStateException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, OPERATOR_LOOKUP_MISS);
        }
        Integer operatorTenantId = operatorWithSchema.operator().tenantId();
        if (!tenantId.equals(operatorTenantId)) {
            log.warn("cross_tenant_operator_access_denied callerTenantId={} operatorTenantId={}",
                    tenantId, operatorTenantId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, OPERATOR_LOOKUP_MISS);
        }
        return operatorWithSchema;
    }

    private String messageOverride(String contactId, String fallbackMessage) {
        if (contactId == null || contactId.isBlank()) {
            return fallbackMessage;
        }
        return fallbackMessage;
    }

    private Optional<BigDecimal> loadWaterNorm(Integer tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return safeFindConfigValue(tenantId, "WATER_NORM")
                .flatMap(raw -> {
                    try {
                        JsonNode root = objectMapper.readTree(raw);
                        String value = root != null ? root.path("value").asText(null) : null;
                        if (value == null || value.isBlank()) {
                            return Optional.empty();
                        }
                        String normalized = value.trim().replace(",", "");
                        if (!normalized.matches("^\\d+(\\.\\d+)?$")) {
                            return Optional.empty();
                        }
                        BigDecimal norm = new BigDecimal(normalized);
                        return norm.compareTo(BigDecimal.ZERO) > 0 ? Optional.of(norm) : Optional.empty();
                    } catch (Exception e) {
                        log.warn("Invalid WATER_NORM config for tenantId {}: {}", tenantId, e.getMessage());
                        return Optional.empty();
                    }
                });
    }

    private Optional<String> safeFindConfigValue(Integer tenantId, String key) {
        Optional<String> opt = tenantConfigRepository.findConfigValue(tenantId, key);
        return opt == null ? Optional.empty() : opt;
    }

    private Optional<WaterSupplyThreshold> loadWaterSupplyThreshold(Integer tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        Optional<String> rawOpt = safeFindConfigValue(tenantId, "TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD")
                .or(() -> safeFindConfigValue(tenantId, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .or(() -> safeFindConfigValue(0, "WATER_QUANTITY_SUPPLY_THRESHOLD"));
        if (rawOpt.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(rawOpt.get());
            if (root == null || root.isNull() || !root.isObject()) {
                return Optional.empty();
            }
            double under = root.path("undersupplyThresholdPercent").asDouble(Double.NaN);
            double over = root.path("oversupplyThresholdPercent").asDouble(Double.NaN);
            if (!Double.isFinite(under) || !Double.isFinite(over)) {
                return Optional.empty();
            }
            if (under < 0.0d || under > 100.0d) {
                return Optional.empty();
            }
            if (over < 0.0d || over > 1000.0d) {
                return Optional.empty();
            }
            return Optional.of(new WaterSupplyThreshold(under, over));
        } catch (Exception e) {
            log.warn("Invalid water supply threshold config for tenantId {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String toPlain(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * SUPPLY-PLAUSIBILITY: one anomaly per operator, per scheme, per day.
     *
     * <p>Analytics derives {@code fact_anomaly_table.uuid} deterministically from the correlationId, and
     * dedups on it — a repeat is touched, not inserted. The date is in the key because the keys
     * analytics builds for itself carry none: without it every type-10 anomaly for an operator and
     * scheme would collapse into a single row forever, and the second day's rejection would be
     * invisible. Same construction as the unreadable-image id in {@code ImageReadingCapture}.
     */
    private String buildSupplyAnomalyCorrelationId(int anomalyType, Long userId, Long schemeId, LocalDate readingDate) {
        String key = anomalyType + ":" + userId + ":" + schemeId + ":" + readingDate;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Records an anomaly on the tenant schema and publishes it, with no dedup of its own — the
     * correlationId decides whether analytics collapses repeats. Named for images until the supply
     * check reused it; nothing in the body was ever image-specific.
     *
     * <p>ANOMALY-SUBMISSION-LINK: {@code flowReadingId} and {@code submissionCorrelationId} are the
     * two halves of the link to the submission that caused the anomaly — the surrogate id for the
     * tenant row, the correlation id for the warehouse, which cannot see a tenant-local id. Both are
     * {@code null} on the paths that reject a submission before any row is written.
     */
    private void recordAnomaly(String schemaName,
                                    Integer tenantId,
                                    Long userId,
                                    Long schemeId,
                                    int anomalyType,
                                    String reason,
                                    int retries,
                                    BigDecimal aiReading,
                                    BigDecimal aiConfidencePercentage,
                                    BigDecimal overriddenReading,
                                    BigDecimal previousReading,
                                    LocalDateTime previousReadingDate,
                                    Integer consecutiveDaysMissed,
                                    String correlationId,
                                    Long flowReadingId,
                                    String submissionCorrelationId) {
        telemetryTenantRepository.createTenantAnomalyRecord(
                schemaName,
                tenantAnomaly(userId, schemeId, anomalyType, reason, retries,
                        aiReading, aiConfidencePercentage, overriddenReading,
                        previousReading, previousReadingDate, flowReadingId)
        );
        telemetryEventPublisher.publishAnomalyRecorded(
                tenantId,
                anomalyType,
                userId,
                schemeId,
                aiReading,
                aiConfidencePercentage,
                overriddenReading,
                retries,
                previousReading,
                previousReadingDate,
                consecutiveDaysMissed,
                reason,
                AnomalyConstants.STATUS_OPEN,
                correlationId,
                submissionCorrelationId
        );
    }

    /**
     * The tenant-schema half of an anomaly, carrying the same numbers as the event published beside
     * it so the two rows agree.
     *
     * <p>{@code consecutiveDaysOverridden} is deliberately left unset. The event's
     * {@code consecutiveDaysMissed} is a different metric and every caller on this path passes zero;
     * the tenant column defaults to 0, so writing the event's value would only invite the two to be
     * read as the same thing. It is filled where a real override run is counted, on the WhatsApp path.
     */
    private static TenantAnomalyRecord tenantAnomaly(Long userId,
                                                     Long schemeId,
                                                     int anomalyType,
                                                     String reason,
                                                     int retries,
                                                     BigDecimal aiReading,
                                                     BigDecimal aiConfidencePercentage,
                                                     BigDecimal overriddenReading,
                                                     BigDecimal previousReading,
                                                     LocalDateTime previousReadingDate,
                                                     Long flowReadingId) {
        return TenantAnomalyRecord.builder()
                .flowReadingId(flowReadingId)
                .userId(userId)
                .schemeId(schemeId)
                .type(anomalyType)
                .reason(reason)
                .status(AnomalyConstants.STATUS_OPEN)
                .aiReading(aiReading)
                .aiConfidencePercentage(aiConfidencePercentage)
                .overriddenReading(overriddenReading)
                .retries(retries)
                .previousReading(previousReading)
                .previousReadingDate(previousReadingDate)
                .build();
    }

    private record WaterSupplyThreshold(double undersupplyThresholdPercent, double oversupplyThresholdPercent) {
    }
}
