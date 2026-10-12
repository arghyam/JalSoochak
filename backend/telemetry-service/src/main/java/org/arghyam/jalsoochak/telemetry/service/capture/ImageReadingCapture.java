package org.arghyam.jalsoochak.telemetry.service.capture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingUnit;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantAnomalyRecord;
import org.arghyam.jalsoochak.telemetry.service.AnomalyConstants;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingExtractor;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderRegistry;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderResolver;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderSettings;
import org.arghyam.jalsoochak.telemetry.service.OcrReadingsRetryService;
import org.arghyam.jalsoochak.telemetry.service.OcrReadingsUnavailableException;
import org.arghyam.jalsoochak.telemetry.service.OcrRetryMode;
import org.arghyam.jalsoochak.telemetry.service.RolloverResolutionService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Captures a reading from a meter photo through OCR.
 *
 * <p>The OCR provider is chosen by channel through {@link OcrProviderRegistry}, so a photo is only ever
 * read by a model for its kind of meter. A channel that doesn't read photos, or has no provider for the
 * tenant (ELM while it has no default provider), is rejected with {@code IMAGE_NOT_SUPPORTED_FOR_CHANNEL};
 * for a channel that doesn't read photos this happens before any OCR setting is read.
 *
 * <p>A photo that can't be read is rejected and recorded as an unreadable-image anomaly, once per
 * attempt. A photo with no meter in it at all (the provider's no-meter verdict) is rejected as
 * {@code NO_METER_DETECTED} and recorded as its own anomaly type instead; a temporary OCR outage asks the submitter to retry.
 *
 * <p>The reading is stored in the unit OCR read it in: the one the provider names (an ELM photo shows
 * kWh or kVAh), or else the channel's standard unit. It is stored as read, so OCR only ever reads a
 * unit {@code confirmed_reading} is stored in: a provider that names another channel's unit, or one
 * that needs converting, fails the read. A {@code reading_unit} that isn't one (L or kL) is rejected
 * before any OCR setting is read. A declared unit must also be the one OCR read: a kWh
 * declaration on a photo of the kVAh register is rejected rather than stored on the wrong register.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImageReadingCapture implements ReadingCapture {

    private static final String UNREADABLE_IMAGE_MESSAGE =
            "Could not read meter value from image. Please retry with a clearer photo.";
    /** Also sent to WhatsApp operators; {@code ConversationLocalizationService} translates it. */
    private static final String NO_METER_MESSAGE =
            "No meter found in the photo. Please send a straight, clear photo of the water meter.";
    /** Also sent to WhatsApp operators; {@code ConversationLocalizationService} translates it. */
    private static final String IMAGE_NOT_SUPPORTED_MESSAGE =
            "Meter photos are not supported for your reading channel.";

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final TelemetryEventPublisher telemetryEventPublisher;
    private final OcrReadingsRetryService ocrReadingsRetryService;
    private final OcrProviderResolver ocrProviderResolver;
    private final OcrProviderRegistry ocrProviderRegistry;

    @Override
    public CaptureOutcome capture(CaptureInput input) {
        if (!input.channel().supportsImageReading()) {
            return imageNotSupported(input);
        }
        Optional<ReadingUnit> declaredUnit = ReadingUnit.parseFor(input.channel(), input.readingUnit());
        Optional<CaptureOutcome> unitRejection = rejectUnitAPhotoIsNotReadIn(input, declaredUnit);
        if (unitRejection.isPresent()) {
            return unitRejection.get();
        }

        try {
            OcrProviderSettings ocrSettings = ocrProviderResolver.resolve(input.tenantId(), input.channel());
            Optional<MeterReadingExtractor> extractor = ocrProviderRegistry.get(
                    input.channel(), ocrSettings == null ? null : ocrSettings.providerId());
            if (extractor.isEmpty()) {
                return imageNotSupported(input);
            }
            OcrReadingResult ocrResult =
                    extractReading(extractor.get(), input.readingUrl(), ocrSettings, input.ocrRetryMode());
            log.info("readings_ocr ocr_result operatorId={} schemeId={} imageUrlHash={} result={}",
                    input.operatorId(),
                    input.schemeId(),
                    imageUrlHash(input.readingUrl()),
                    summarizeOcrResult(ocrResult));
            if (ocrResult != null && ocrResult.isNoMeter()) {
                recordImageAnomaly(input, AnomalyConstants.TYPE_NO_METER_DETECTED,
                        AnomalyConstants.REASON_NO_METER_DETECTED);
                return new CaptureOutcome.Rejected(TelemetryErrorCode.NO_METER_DETECTED, NO_METER_MESSAGE);
            }
            if (ocrResult == null || ocrResult.getAdjustedReading() == null) {
                recordUnreadableImage(input, "Unreadable image. OCR could not extract a valid meter reading.");
                return new CaptureOutcome.Rejected(TelemetryErrorCode.UNREADABLE_IMAGE, unreadableImageMessage(ocrResult));
            }
            log.info("readings_ocr ocr_accepted operatorId={} schemeId={} correlationId={} adjustedReading={} confidence={} qualityStatus={}",
                    input.operatorId(),
                    input.schemeId(),
                    sanitizeLogValue(ocrResult.getCorrelationId()),
                    ocrResult.getAdjustedReading(),
                    ocrResult.getQualityConfidence(),
                    sanitizeLogValue(ocrResult.getQualityStatus()));
            ReadingUnit readUnit = readUnit(ocrResult, input.channel());
            if (!isStoredAsRead(readUnit, input.channel())) {
                return unitNotStoredAsRead(input, readUnit);
            }
            if (declaredUnit.isPresent() && declaredUnit.get() != readUnit) {
                return unitNotTheOneRead(input, declaredUnit.get(), readUnit);
            }
            return new CaptureOutcome.Captured(new CapturedReading(
                    ocrResult.getAdjustedReading(),
                    readUnit,
                    ocrResult.getAdjustedReading(),
                    ocrResult.getQualityConfidence(),
                    RolloverResolutionService.SOURCE_AS_EXTRACTED,
                    ocrResult));
        } catch (OcrReadingsUnavailableException ex) {
            log.warn("OCR temporarily unavailable for imageUrlHash={}: {}",
                    imageUrlHash(input.readingUrl()),
                    ex.getMessage());
            return new CaptureOutcome.Retry(
                    "Meter reading service is temporarily unavailable. Please try again shortly.");
        } catch (Exception ex) {
            log.error("OCR failed for imageUrlHash={}: {}", imageUrlHash(input.readingUrl()), ex.getMessage(), ex);
            if (log.isDebugEnabled()) {
                log.debug("OCR failed for URL: {}", input.readingUrl());
            }
            recordUnreadableImage(input, "Unreadable image. OCR failed during extraction.");
            return new CaptureOutcome.Rejected(TelemetryErrorCode.FLOW_VISION_FAILED, UNREADABLE_IMAGE_MESSAGE);
        }
    }

    /**
     * A photo-only submission may name a unit OCR reads the channel's meter in, but no other: OCR has no
     * way to read a meter in litres.
     */
    private static Optional<CaptureOutcome> rejectUnitAPhotoIsNotReadIn(CaptureInput input,
                                                                       Optional<ReadingUnit> declared) {
        if (!ReadingUnit.isDeclared(input.readingUnit())) {
            return Optional.empty();
        }
        ReadingChannel channel = input.channel();
        if (declared.isEmpty()) {
            return Optional.of(new CaptureOutcome.Rejected(
                    TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                    ReadingUnit.unsupportedMessage(channel)));
        }
        if (isReadFromPhotos(declared.get())) {
            return Optional.empty();
        }
        String photoUnits = Arrays.stream(ReadingUnit.values())
                .filter(unit -> unit.channel() == channel && isReadFromPhotos(unit))
                .map(ReadingUnit::code)
                .collect(Collectors.joining(" or "));
        return Optional.of(new CaptureOutcome.Rejected(
                TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                "reading_unit applies only to confirmed_reading. A meter photo for channel " + channel.name()
                        + " is read in " + photoUnits + "."));
    }

    /** OCR's value is stored as it is read, so a photo is only ever read in a unit readings are stored in. */
    private static boolean isReadFromPhotos(ReadingUnit unit) {
        return unit.storedUnit() == unit;
    }

    /** Whether OCR's value can be stored as it was read: a unit of the photo's channel that needs no converting. */
    private static boolean isStoredAsRead(ReadingUnit unit, ReadingChannel channel) {
        return unit.channel() == channel && isReadFromPhotos(unit);
    }

    /**
     * The provider named a unit its value can't be stored in as read: another channel's, or one that
     * would need converting (L, kL). The provider is at fault rather than the photo, so this is handled
     * like any other failed read, and the value is never stored in the wrong unit.
     */
    private CaptureOutcome unitNotStoredAsRead(CaptureInput input, ReadingUnit readUnit) {
        log.error("readings_ocr unit_not_stored_as_read operatorId={} schemeId={} channel={} unit={}",
                input.operatorId(), input.schemeId(), input.channel(), readUnit.code());
        recordUnreadableImage(input, "Unreadable image. OCR read the meter in a unit its reading can't be stored in.");
        return new CaptureOutcome.Rejected(TelemetryErrorCode.FLOW_VISION_FAILED, UNREADABLE_IMAGE_MESSAGE);
    }

    /** The photo shows another register than the one declared. Not an unreadable image, so no anomaly. */
    private static CaptureOutcome unitNotTheOneRead(CaptureInput input, ReadingUnit declared, ReadingUnit read) {
        log.info("readings_ocr unit_mismatch operatorId={} schemeId={} declared={} read={}",
                input.operatorId(), input.schemeId(), declared.code(), read.code());
        return new CaptureOutcome.Rejected(
                TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                "reading_unit is " + declared.code() + ", but the meter photo shows a reading in "
                        + read.code() + ".");
    }

    /** The unit OCR read the meter in: the one the provider named, or else the channel's standard unit. */
    private static ReadingUnit readUnit(OcrReadingResult ocrResult, ReadingChannel channel) {
        return Optional.ofNullable(ocrResult.getReadingUnit())
                .or(channel::standardUnit)
                .orElse(null);
    }

    /**
     * The channel has no way to read a photo: it doesn't read photos at all, or no provider exists for it
     * yet. Not an unreadable image, so no anomaly is recorded.
     */
    private static CaptureOutcome imageNotSupported(CaptureInput input) {
        log.info("readings_ocr image_not_supported operatorId={} schemeId={} channel={}",
                input.operatorId(), input.schemeId(), input.channel());
        return new CaptureOutcome.Rejected(TelemetryErrorCode.IMAGE_NOT_SUPPORTED_FOR_CHANNEL, IMAGE_NOT_SUPPORTED_MESSAGE);
    }

    /**
     * Runs OCR for {@code readingUrl} through {@code extractor}. {@code null} settings mean the tenant has
     * no override, so the extractor uses its own configuration. Honours the resilient
     * (retry/circuit-breaker) path.
     */
    private OcrReadingResult extractReading(MeterReadingExtractor extractor, String readingUrl,
                                            OcrProviderSettings settings, OcrRetryMode ocrRetryMode) {
        if (ocrRetryMode == OcrRetryMode.RESILIENT) {
            return ocrReadingsRetryService.extractReading(extractor, readingUrl, settings);
        }
        return extractor.extractReading(readingUrl, settings);
    }

    private void recordUnreadableImage(CaptureInput input, String reason) {
        recordImageAnomaly(input, AnomalyConstants.TYPE_UNREADABLE_IMAGE, reason);
    }

    /** A rejected photo: no reading row is written, so the anomaly has no submission to point at. */
    private void recordImageAnomaly(CaptureInput input, int anomalyType, String reason) {
        telemetryTenantRepository.createTenantAnomalyRecord(
                input.schemaName(),
                TenantAnomalyRecord.builder()
                        .userId(input.operatorId())
                        .schemeId(input.schemeId())
                        .type(anomalyType)
                        .reason(reason)
                        .status(AnomalyConstants.STATUS_OPEN)
                        .retries(1)
                        .build());
        telemetryEventPublisher.publishAnomalyRecorded(
                input.tenantId(),
                anomalyType,
                input.operatorId(),
                input.schemeId(),
                null,
                null,
                null,
                1,
                null,
                null,
                0,
                reason,
                AnomalyConstants.STATUS_OPEN,
                imageAnomalyCorrelationId(input, anomalyType),
                // ANOMALY-SUBMISSION-LINK: the submission is rejected here, before any
                // flow_reading_table row is written, so there is nothing to point at.
                null);
    }

    /** The same photo gives the same id on every attempt, so analytics keeps one anomaly for it. */
    private static String imageAnomalyCorrelationId(CaptureInput input, int anomalyType) {
        String normalizedUrl = input.readingUrl() == null ? "" : input.readingUrl().trim();
        String key = anomalyType + ":" + input.operatorId() + ":"
                + input.schemeId() + ":" + normalizedUrl;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String unreadableImageMessage(OcrReadingResult result) {
        String rejectionReason = Optional.ofNullable(result)
                .map(OcrReadingResult::getRejectionReason)
                .filter(reason -> !reason.isBlank())
                .orElse(null);
        if (rejectionReason == null) {
            return UNREADABLE_IMAGE_MESSAGE;
        }
        return "Could not read meter value from image. " + rejectionReason;
    }

    private static String summarizeOcrResult(OcrReadingResult result) {
        if (result == null) {
            return "null";
        }
        return String.format(
                "{adjustedReading=%s,qualityStatus=%s,qualityConfidence=%s,correlationId=%s}",
                result.getAdjustedReading(),
                sanitizeLogValue(result.getQualityStatus()),
                result.getQualityConfidence(),
                sanitizeLogValue(result.getCorrelationId())
        );
    }

    private static String imageUrlHash(String readingUrl) {
        if (readingUrl == null || readingUrl.isBlank()) {
            return "n/a";
        }
        return Integer.toHexString(readingUrl.hashCode());
    }

    private static String sanitizeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
