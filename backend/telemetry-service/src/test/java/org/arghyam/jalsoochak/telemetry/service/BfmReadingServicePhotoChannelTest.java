package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.FlowReadingVersion;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.capture.ImageReadingCapture;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimit;
import org.arghyam.jalsoochak.telemetry.service.capture.SubmittedValueCapture;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityFixtures;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which channels read a meter photo. The OCR provider is chosen by channel through the real resolver and
 * registry, with only BFM's provider registered, as in production today.
 */
@ExtendWith(MockitoExtension.class)
class BfmReadingServicePhotoChannelTest {

    private static final String SCHEMA = "tenant_test";
    private static final long SCHEME_ID = 10L;
    private static final long OPERATOR_ID = 1L;
    private static final int TENANT_ID = 1;
    private static final String CONTACT = "91XXXXXXXXXX";
    private static final String IMAGE_URL = "https://img.example.com/meter.jpg";

    @Mock
    private PduDayLimit pduDayLimit;

    @Mock
    private CalculationParametersSnapshotter calculationParametersSnapshotter;

    @Mock
    private TelemetryTenantRepository repo;
    @Mock
    private MeterReadingExtractor bfmOcrExtractor;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private ReadingChannelResolver readingChannelResolver;

    private BfmReadingService service;
    private final TelemetryOperator operator =
            new TelemetryOperator(OPERATOR_ID, TENANT_ID, "op", "op@example.com", CONTACT, null);

    @BeforeEach
    void setUp() {
        OcrProviderResolver ocrProviderResolver = new OcrProviderResolver(
                tenantConfigRepository, new MockEnvironment(), OcrProviderSettings.DEFAULT_PROVIDER_ID,
                "https://flowvision.example/extract", "bfm-key", OcrProviderSettings.DEFAULT_AUTH_HEADER);
        service = new BfmReadingService(
                repo,
                telemetryEventPublisher,
                null,
                tenantConfigRepository,
                new ObjectMapper(),
                operatorContextService,
                readingChannelResolver,
                new RolloverResolutionService(false, new ObjectMapper()),
                SupplyPlausibilityFixtures.guard(
                        SupplyPlausibilityProperties.Mode.AUDIT, repo, tenantConfigRepository),
                new ImageReadingCapture(
                        repo,
                        telemetryEventPublisher,
                        null,
                        ocrProviderResolver,
                        OcrFixtures.registryWithBfmDefault(bfmOcrExtractor)),
                new SubmittedValueCapture(),
                pduDayLimit,
                calculationParametersSnapshotter,
                null);
        lenient().when(repo.existsSchemeById(SCHEMA, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findOperatorById(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(operator));
        lenient().when(repo.isOperatorMappedToScheme(SCHEMA, OPERATOR_ID, SCHEME_ID)).thenReturn(true);
    }

    @ParameterizedTest
    @EnumSource(value = ReadingChannel.class, names = {"PDU", "ELM"})
    @DisplayName("a photo alone is refused for PDU, and for ELM while the tenant has no ELM OCR keys")
    void photoAloneIsRefused(ReadingChannel channel) {
        when(readingChannelResolver.resolve(SCHEMA, CONTACT)).thenReturn(channel);

        CreateReadingResponse response = service.createReading(photo(null), SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.IMAGE_NOT_SUPPORTED_FOR_CHANNEL);
        assertThat(response.getMessage()).isEqualTo("Meter photos are not supported for your reading channel.");
        verify(bfmOcrExtractor, never()).extractReading(anyString(), any());
        verifyNothingStoredOrPublished();
    }

    @Test
    @DisplayName("a PDU photo is refused without reading any OCR config")
    void pduPhotoReadsNoOcrConfig() {
        when(readingChannelResolver.resolve(SCHEMA, CONTACT)).thenReturn(ReadingChannel.PDU);

        service.createReading(photo(null), SCHEMA, operator, CONTACT, false);

        verify(tenantConfigRepository, never()).findConfigValue(any(), startsWith("ocr"));
    }

    @Test
    @DisplayName("an ELM photo reads only the ELM keys, and never reaches the BFM provider")
    void elmPhotoNeverFallsBackToBfm() {
        when(readingChannelResolver.resolve(SCHEMA, CONTACT)).thenReturn(ReadingChannel.ELM);
        lenient().when(tenantConfigRepository.findConfigValue(TENANT_ID, "ocr_provider"))
                .thenReturn(Optional.of(OcrProviderSettings.DEFAULT_PROVIDER_ID));

        CreateReadingResponse response = service.createReading(photo(null), SCHEMA, operator, CONTACT, false);

        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.IMAGE_NOT_SUPPORTED_FOR_CHANNEL);
        verify(tenantConfigRepository).findConfigValue(TENANT_ID, "ocr_elm_provider");
        verify(tenantConfigRepository, never()).findConfigValue(TENANT_ID, "ocr_provider");
        verify(bfmOcrExtractor, never()).extractReading(anyString(), any());
    }

    @Test
    @DisplayName("a PDU photo sent with a typed value is stored with the photo, and OCR doesn't run")
    void pduPhotoWithATypedValueIsAccepted() {
        lenient().when(repo.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME_ID, ReadingChannel.BFM, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(
                        new BigDecimal("30"), ReadingTime.now().minusDays(1))));
        lenient().when(repo.findLatestPlaceholderFlowReadingIdForDate(eq(SCHEMA), eq(SCHEME_ID), eq(OPERATOR_ID),
                any(LocalDate.class))).thenReturn(Optional.empty());
        when(repo.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(), any(),
                any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new FlowReadingVersion(99L, null));
        CreateReadingRequest request = CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingUrl(IMAGE_URL)
                .readingValue(new BigDecimal("45"))
                .externallyAsserted(true)
                .declaredChannel(ReadingChannel.PDU)
                .build();

        CreateReadingResponse response = service.createReading(request, SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isTrue();
        verify(repo).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(), any(),
                eq(IMAGE_URL), any(), anyInt(), any(), any(), any(), any(), any(), eq("PDU"), eq("min"));
        verify(bfmOcrExtractor, never()).extractReading(anyString(), any());
        verify(tenantConfigRepository, never()).findConfigValue(any(), startsWith("ocr"));
    }

    private void verifyNothingStoredOrPublished() {
        verify(repo, never()).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(), any(),
                any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                any(BigDecimal.class), any(BigDecimal.class), anyString(), any(), any(), any(), any(), any());
        verify(repo, never()).createTenantAnomalyRecord(anyString(), any());
        verifyNoInteractions(telemetryEventPublisher);
    }

    private static CreateReadingRequest photo(String unit) {
        return CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingUrl(IMAGE_URL)
                .readingUnit(unit)
                .build();
    }
}
