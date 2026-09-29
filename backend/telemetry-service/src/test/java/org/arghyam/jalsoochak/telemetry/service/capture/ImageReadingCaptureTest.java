package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingUnit;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.AnomalyConstants;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingExtractor;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderRegistry;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderResolver;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderSettings;
import org.arghyam.jalsoochak.telemetry.service.OcrReadingsRetryService;
import org.arghyam.jalsoochak.telemetry.service.OcrReadingsUnavailableException;
import org.arghyam.jalsoochak.telemetry.service.OcrRetryMode;
import org.arghyam.jalsoochak.telemetry.service.RolloverResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageReadingCaptureTest {

    private static final String SCHEMA = "tenant_test";
    private static final int TENANT_ID = 7;
    private static final long OPERATOR_ID = 11L;
    private static final long SCHEME_ID = 100L;
    private static final String IMAGE_URL = "https://img.example.com/meter.jpg";

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private MeterReadingExtractor defaultOcrExtractor;
    @Mock
    private MeterReadingExtractor tenantOcrExtractor;
    @Mock
    private OcrReadingsRetryService ocrReadingsRetryService;
    @Mock
    private OcrProviderResolver ocrProviderResolver;
    @Mock
    private OcrProviderRegistry ocrProviderRegistry;

    private ImageReadingCapture capture;

    @BeforeEach
    void setUp() {
        capture = new ImageReadingCapture(
                telemetryTenantRepository,
                telemetryEventPublisher,
                defaultOcrExtractor,
                ocrReadingsRetryService,
                ocrProviderResolver,
                ocrProviderRegistry);
    }

    @Test
    @DisplayName("a readable photo is captured in the channel's standard unit with its OCR details")
    void readablePhotoIsCaptured() {
        OcrReadingResult ocr = readable("123.45", "0.95");
        when(defaultOcrExtractor.extractReading(IMAGE_URL, null)).thenReturn(ocr);

        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, null, OcrRetryMode.NONE));

        assertThat(outcome).isInstanceOf(CaptureOutcome.Captured.class);
        CapturedReading reading = ((CaptureOutcome.Captured) outcome).reading();
        assertThat(reading.value()).isEqualByComparingTo("123.45");
        assertThat(reading.submittedUnit()).isEqualTo(ReadingUnit.CUBIC_METRE);
        assertThat(reading.extractedReading()).isEqualByComparingTo("123.45");
        assertThat(reading.confidence()).isEqualByComparingTo("0.95");
        assertThat(reading.source()).isEqualTo(RolloverResolutionService.SOURCE_AS_EXTRACTED);
        assertThat(reading.ocrResult()).isSameAs(ocr);
        verifyNoInteractions(telemetryEventPublisher);
    }

    @Test
    @DisplayName("naming the channel's standard unit with a photo changes nothing")
    void standardUnitWithAPhotoIsAccepted() {
        when(defaultOcrExtractor.extractReading(IMAGE_URL, null)).thenReturn(readable("123", "0.95"));

        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, " M3 ", OcrRetryMode.NONE));

        assertThat(outcome).isInstanceOf(CaptureOutcome.Captured.class);
    }

    @Test
    @DisplayName("another unit of the channel with only a photo is rejected before any OCR setting is read")
    void nonStandardUnitWithAPhotoIsRejected() {
        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, "L", OcrRetryMode.NONE));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Rejected(
                TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                "reading_unit applies only to confirmed_reading. A meter photo for channel BFM is read in m3."));
        verifyNoInteractions(ocrProviderResolver, defaultOcrExtractor, ocrReadingsRetryService,
                telemetryTenantRepository, telemetryEventPublisher);
    }

    @Test
    @DisplayName("a unit the channel doesn't accept is rejected before any OCR setting is read")
    void unitOfAnotherChannelWithAPhotoIsRejected() {
        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, "kW.h", OcrRetryMode.NONE));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Rejected(
                TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                ReadingUnit.unsupportedMessage(ReadingChannel.BFM)));
        verifyNoInteractions(ocrProviderResolver, defaultOcrExtractor);
    }

    @Test
    @DisplayName("a channel with no units rejects any declared unit")
    void channelWithNoUnitsRejectsAnyUnit() {
        CaptureOutcome outcome = capture.capture(input(ReadingChannel.IOT, "m3", OcrRetryMode.NONE));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Rejected(
                TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                ReadingUnit.unsupportedMessage(ReadingChannel.IOT)));
        verifyNoInteractions(ocrProviderResolver);
    }

    @Test
    @DisplayName("no reading from OCR is rejected as unreadable and recorded as an anomaly")
    void noReadingIsUnreadable() {
        when(defaultOcrExtractor.extractReading(IMAGE_URL, null)).thenReturn(null);

        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, null, OcrRetryMode.NONE));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Rejected(
                TelemetryErrorCode.UNREADABLE_IMAGE,
                "Could not read meter value from image. Please retry with a clearer photo."));
        verifyUnreadableImageAnomaly("Unreadable image. OCR could not extract a valid meter reading.");
    }

    @Test
    @DisplayName("an unreadable photo's message carries the provider's rejection reason")
    void unreadableMessageCarriesTheRejectionReason() {
        when(defaultOcrExtractor.extractReading(IMAGE_URL, null)).thenReturn(
                OcrReadingResult.builder().rejectionReason("Meter face is blurred.").build());

        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, null, OcrRetryMode.NONE));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Rejected(
                TelemetryErrorCode.UNREADABLE_IMAGE,
                "Could not read meter value from image. Meter face is blurred."));
    }

    @Test
    @DisplayName("an OCR failure is rejected and recorded as an unreadable-image anomaly")
    void ocrFailureIsRejected() {
        when(defaultOcrExtractor.extractReading(IMAGE_URL, null)).thenThrow(new IllegalStateException("boom"));

        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, null, OcrRetryMode.NONE));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Rejected(
                TelemetryErrorCode.FLOW_VISION_FAILED,
                "Could not read meter value from image. Please retry with a clearer photo."));
        verifyUnreadableImageAnomaly("Unreadable image. OCR failed during extraction.");
    }

    @Test
    @DisplayName("a temporary OCR outage asks for a retry and records no anomaly")
    void ocrOutageAsksForARetry() {
        when(ocrReadingsRetryService.extractReading(IMAGE_URL))
                .thenThrow(new OcrReadingsUnavailableException("circuit open", new RuntimeException("timeout")));

        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, null, OcrRetryMode.RESILIENT));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Retry(
                "Meter reading service is temporarily unavailable. Please try again shortly."));
        verifyNoInteractions(telemetryTenantRepository, telemetryEventPublisher);
    }

    @Test
    @DisplayName("the resilient mode reads through the retry layer, with the tenant's settings when it has them")
    void resilientModeGoesThroughTheRetryLayer() {
        OcrProviderSettings settings = new OcrProviderSettings("custom", "https://ocr.example.com", "key", "X-Key");
        when(ocrProviderResolver.resolve(TENANT_ID)).thenReturn(settings);
        when(ocrReadingsRetryService.extractReading(IMAGE_URL, settings)).thenReturn(readable("50", "0.9"));

        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, null, OcrRetryMode.RESILIENT));

        assertThat(outcome).isInstanceOf(CaptureOutcome.Captured.class);
        verifyNoInteractions(defaultOcrExtractor, ocrProviderRegistry);
    }

    @Test
    @DisplayName("a tenant's own OCR provider is dispatched through the registry")
    void tenantProviderIsDispatchedThroughTheRegistry() {
        OcrProviderSettings settings = new OcrProviderSettings("custom", "https://ocr.example.com", "key", "X-Key");
        when(ocrProviderResolver.resolve(TENANT_ID)).thenReturn(settings);
        when(ocrProviderRegistry.get("custom")).thenReturn(tenantOcrExtractor);
        when(tenantOcrExtractor.extractReading(IMAGE_URL, settings)).thenReturn(readable("50", "0.9"));

        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, null, OcrRetryMode.NONE));

        assertThat(outcome).isInstanceOf(CaptureOutcome.Captured.class);
        verify(defaultOcrExtractor, never()).extractReading(anyString(), any());
    }

    private void verifyUnreadableImageAnomaly(String reason) {
        verify(telemetryTenantRepository).createTenantAnomalyRecord(eq(SCHEMA), argThat(anomaly ->
                anomaly.userId() == OPERATOR_ID
                        && anomaly.schemeId() == SCHEME_ID
                        && anomaly.type() == AnomalyConstants.TYPE_UNREADABLE_IMAGE
                        && anomaly.status() == AnomalyConstants.STATUS_OPEN
                        && anomaly.retries() == 1
                        && anomaly.flowReadingId() == null
                        && reason.equals(anomaly.reason())));
        verify(telemetryEventPublisher).publishAnomalyRecorded(
                eq(TENANT_ID), eq(AnomalyConstants.TYPE_UNREADABLE_IMAGE), eq(OPERATOR_ID), eq(SCHEME_ID),
                isNull(), isNull(), isNull(), eq(1), isNull(), isNull(), eq(0), eq(reason),
                eq(AnomalyConstants.STATUS_OPEN), anyString(), isNull());
    }

    private static OcrReadingResult readable(String reading, String confidence) {
        return OcrReadingResult.builder()
                .adjustedReading(new BigDecimal(reading))
                .qualityConfidence(new BigDecimal(confidence))
                .qualityStatus("GOOD")
                .correlationId("ocr-correlation")
                .build();
    }

    private static CaptureInput input(ReadingChannel channel, String readingUnit, OcrRetryMode mode) {
        return new CaptureInput(SCHEMA, TENANT_ID, OPERATOR_ID, SCHEME_ID, channel, IMAGE_URL,
                null, readingUnit, false, mode);
    }
}
