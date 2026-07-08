package org.arghyam.jalsoochak.telemetry.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.GlificWebhookRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificImageWorkflowServiceAssamTest {

    @Mock
    private GlificMediaService glificMediaService;

    @Mock
    private BfmReadingService bfmReadingService;

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @Mock
    private GlificOperatorContextService operatorContextService;

    @Mock
    private GlificLocalizationService localizationService;

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private GlificImageWorkflowService service;

    @Test
    void processImageDoesNotRequireSelectedChannel() throws Exception {
        GlificWebhookRequest request = GlificWebhookRequest.builder()
                .contactId("919876543210")
                .mediaId("media-1")
                .mediaUrl("https://example.com/meter.jpg")
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(glificMediaService.downloadImage("media-1", "https://example.com/meter.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(glificMediaService.uploadImage("919876543210", new byte[]{1, 2, 3})).thenReturn("https://cdn.example.com/meter.jpg");
        when(operatorContextService.resolveOperatorWithSchema("919876543210")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate("tenant_test", 11L, java.time.LocalDate.now()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 11L)).thenReturn(Optional.of(101L));
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");

        CreateReadingResponse response = service.processImage(request);

        assertNotNull(response);
        assertEquals(true, response.isSuccess());
        verify(bfmReadingService).createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    void processImageContinuesWhenSelectedChannelMissingOrRemoved() throws Exception {
        GlificWebhookRequest request = GlificWebhookRequest.builder()
                .contactId("919876543210")
                .mediaId("media-1")
                .mediaUrl("https://example.com/meter.jpg")
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(glificMediaService.downloadImage("media-1", "https://example.com/meter.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(glificMediaService.uploadImage("919876543210", new byte[]{1, 2, 3})).thenReturn("https://cdn.example.com/meter.jpg");
        when(operatorContextService.resolveOperatorWithSchema("919876543210")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate("tenant_test", 11L, java.time.LocalDate.now()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 11L)).thenReturn(Optional.of(101L));
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");

        CreateReadingResponse response = service.processImage(request);

        assertNotNull(response);
        assertEquals(true, response.isSuccess());
        verify(bfmReadingService).createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    void processAssamReadingSkipsLocationUpdateWhenGeolocationMissing() {
        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .centreSchemeId("30244993")
                .phoneNumber("919876543210")
                .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"))
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_assam",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(operatorContextService.tryResolveOperatorWithSchema("919876543210", 22))
                .thenReturn(Optional.of(operatorWithSchema));
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_assam", 11L, 30178236L)).thenReturn(true);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertEquals(true, response.isSuccess());
        verify(telemetryTenantRepository, never()).updateReadingLocation(anyString(), any(), any(), any(), any());
    }

    @Test
    void processAssamReadingUpdatesLocationWhenGeolocationPresent() {
        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .centreSchemeId("30244993")
                .phoneNumber("919876543210")
                .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"))
                .geolocation(AssamReadingRequest.Geolocation.builder()
                        .type("Point")
                        .coordinates(List.of(new BigDecimal("56.78"), new BigDecimal("12.34")))
                        .build())
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_assam",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(operatorContextService.tryResolveOperatorWithSchema("919876543210", 22))
                .thenReturn(Optional.of(operatorWithSchema));
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId("tenant_assam", "30244993"))
                .thenReturn(Optional.of(30244993L));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_assam", 11L, 30244993L)).thenReturn(true);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());
        when(telemetryTenantRepository.findReadingByCorrelationId("tenant_assam", "corr-1"))
                .thenReturn(Optional.of(new TelemetryReadingRecord(100L, "corr-1", 11L)));

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertEquals(true, response.isSuccess());

        ArgumentCaptor<CreateReadingRequest> requestCaptor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean());
        assertEquals(30244993L, requestCaptor.getValue().getSchemeId());
        assertEquals(new BigDecimal("123.4"), requestCaptor.getValue().getReadingValue());

        verify(telemetryTenantRepository).updateReadingLocation(
                "tenant_assam",
                100L,
                new BigDecimal("12.34"),
                new BigDecimal("56.78"),
                11L
        );
    }

    @Test
    void processAssamReadingAllowsMissingReadingUrlWhenConfirmedReadingProvided() {
        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl(null)
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .phoneNumber("919876543210")
                .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"))
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_assam",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(operatorContextService.tryResolveOperatorWithSchema("919876543210", 22))
                .thenReturn(Optional.of(operatorWithSchema));
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_assam", 11L, 30178236L)).thenReturn(true);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertEquals(true, response.isSuccess());

        ArgumentCaptor<CreateReadingRequest> requestCaptor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean());
        assertNull(requestCaptor.getValue().getReadingUrl());
        assertEquals(new BigDecimal("123.4"), requestCaptor.getValue().getReadingValue());
    }

    @Test
    void processAssamReadingAllowsMissingReadingDateTime() {
        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .phoneNumber("919876543210")
                .readingDateTime(null)
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_assam",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(operatorContextService.tryResolveOperatorWithSchema("919876543210", 22))
                .thenReturn(Optional.of(operatorWithSchema));
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_assam", 11L, 30178236L)).thenReturn(true);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertEquals(true, response.isSuccess());

        ArgumentCaptor<CreateReadingRequest> requestCaptor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean());
        assertNull(requestCaptor.getValue().getReadingTime());
    }

    @Test
    void processAssamReadingRecordsAgainstPlaceholderWhenSchemeIdsUnknown() {
        // LENIENT-INGEST: unknown scheme ids are now recorded against an auto-provisioned placeholder
        // scheme (and tagged UNKNOWN_SCHEME) instead of being rejected.
        ReflectionTestUtils.setField(service, "lenientIngestionEnabled", true);

        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("99999999")
                .centreSchemeId("88888888")
                .phoneNumber("919876543210")
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_assam",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(operatorContextService.tryResolveOperatorWithSchema("919876543210", 22))
                .thenReturn(Optional.of(operatorWithSchema));
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "99999999"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId("tenant_assam", "88888888"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.getOrCreatePlaceholderScheme("tenant_assam", "99999999", "88888888"))
                .thenReturn(55555L);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");

        ListAppender<ILoggingEvent> appender = attachAppender();
        CreateReadingResponse response;
        try {
            response = service.processAssamReading(request, 22);
        } finally {
            detachAppender(appender);
        }

        assertNotNull(response);
        assertTrue(response.isSuccess());

        ArgumentCaptor<CreateReadingRequest> requestCaptor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean());
        CreateReadingRequest captured = requestCaptor.getValue();
        assertEquals(55555L, captured.getSchemeId());
        assertNotNull(captured.getIngestionSource());
        assertTrue((captured.getIngestionSource() & IngestionSource.UNKNOWN_SCHEME) != 0,
                "Reading should be tagged UNKNOWN_SCHEME");
        assertEquals("99999999", captured.getSubmittedStateSchemeId());
        // Scheme ids are not PII and must be logged so the auto-provisioning can be traced.
        assertTrue(infoLogged(appender, "reason=\"scheme_not_found\""));
        assertTrue(infoLogged(appender, "auto_provisioned_scheme_id=55555"));
    }

    @Test
    void processAssamReadingRecordsAgainstExistingSchemeWhenOperatorNotMapped() {
        // LENIENT-INGEST: when the scheme exists but the operator is not mapped to it, the reading is
        // now recorded against that scheme and tagged OPERATOR_NOT_MAPPED instead of being rejected.
        ReflectionTestUtils.setField(service, "lenientIngestionEnabled", true);

        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .centreSchemeId("30244993")
                .phoneNumber("919876543210")
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_assam",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(operatorContextService.tryResolveOperatorWithSchema("919876543210", 22))
                .thenReturn(Optional.of(operatorWithSchema));
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_assam", 11L, 30178236L))
                .thenReturn(false);
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId("tenant_assam", "30244993"))
                .thenReturn(Optional.empty());
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");

        ListAppender<ILoggingEvent> appender = attachAppender();
        CreateReadingResponse response;
        try {
            response = service.processAssamReading(request, 22);
        } finally {
            detachAppender(appender);
        }

        assertNotNull(response);
        assertTrue(response.isSuccess());

        ArgumentCaptor<CreateReadingRequest> requestCaptor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean());
        CreateReadingRequest captured = requestCaptor.getValue();
        assertEquals(30178236L, captured.getSchemeId());
        assertNotNull(captured.getIngestionSource());
        assertTrue((captured.getIngestionSource() & IngestionSource.OPERATOR_NOT_MAPPED) != 0,
                "Reading should be tagged OPERATOR_NOT_MAPPED");
        assertTrue(infoLogged(appender, "reason=\"operator_not_mapped_to_scheme\""));
        assertFalse(infoLogged(appender, "scheme_not_found"),
                "Should not log scheme_not_found when the scheme exists");
        assertTrue(infoLogged(appender, "stateSchemeFound=true"));
    }

    @Test
    void processAssamReadingRecordsAgainstSentinelWhenOperatorNotFoundAndNeverLogsRawPhoneAboveDebug() {
        // LENIENT-INGEST: an unregistered phone is now recorded against the sentinel "Unknown operator"
        // (tagged UNKNOWN_OPERATOR). The raw phone must still never appear above DEBUG.
        ReflectionTestUtils.setField(service, "lenientIngestionEnabled", true);

        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .phoneNumber("919876543210")
                .build();

        TelemetryOperator sentinel = new TelemetryOperator(
                999L, 22, "Unknown Operator", "unknown-operator@auto.jalsoochak.invalid", "UNKNOWN", null);
        TelemetryOperatorWithSchema sentinelWithSchema = new TelemetryOperatorWithSchema("tenant_assam", sentinel);

        when(operatorContextService.tryResolveOperatorWithSchema("919876543210", 22))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemaNameByTenantId(22)).thenReturn(Optional.of("tenant_assam"));
        when(telemetryTenantRepository.getOrCreateUnknownOperatorUserId("tenant_assam", 22)).thenReturn(999L);
        when(telemetryTenantRepository.findOperatorById("tenant_assam", 999L)).thenReturn(Optional.of(sentinel));
        when(telemetryTenantRepository.hashSubmittedPhone("919876543210")).thenReturn("phv-hash");
        when(operatorContextService.resolveOperatorLanguage(sentinelWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_assam", 999L, 30178236L))
                .thenReturn(false);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlificImageWorkflowService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);

        CreateReadingResponse response;
        try {
            response = service.processAssamReading(request, 22);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        assertNotNull(response);
        assertTrue(response.isSuccess());

        ArgumentCaptor<CreateReadingRequest> requestCaptor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean());
        CreateReadingRequest captured = requestCaptor.getValue();
        assertTrue((captured.getIngestionSource() & IngestionSource.UNKNOWN_OPERATOR) != 0,
                "Reading should be tagged UNKNOWN_OPERATOR");
        assertEquals("phv-hash", captured.getSubmittedPhoneHash());

        boolean rawAboveDebug = appender.list.stream()
                .filter(event -> event.getLevel() != Level.DEBUG)
                .anyMatch(event -> event.getFormattedMessage().contains("919876543210"));
        assertFalse(rawAboveDebug, "Raw phone must not appear in INFO/WARN/ERROR logs");

        boolean rawAtDebug = appender.list.stream()
                .filter(event -> event.getLevel() == Level.DEBUG)
                .anyMatch(event -> event.getFormattedMessage().contains("919876543210"));
        assertTrue(rawAtDebug, "Raw phone should be available at DEBUG level");
    }

    @Test
    void processAssamReadingRejectsUnknownSchemeWhenLenientIngestionDisabled() {
        // With the LENIENT-INGEST off-switch disabled, the original reject behaviour is preserved.
        ReflectionTestUtils.setField(service, "lenientIngestionEnabled", false);

        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("99999999")
                .centreSchemeId("88888888")
                .phoneNumber("919876543210")
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_assam",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(operatorContextService.tryResolveOperatorWithSchema("919876543210", 22))
                .thenReturn(Optional.of(operatorWithSchema));
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "99999999"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId("tenant_assam", "88888888"))
                .thenReturn(Optional.empty());
        when(localizationService.resolveLanguageKeyForContact("919876543210")).thenReturn("english");
        when(localizationService.resolveUserFacingErrorMessage(any(), anyString(), anyString()))
                .thenReturn("Reading rejected");

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("REJECTED", response.getQualityStatus());
        assertEquals(TelemetryErrorCode.OPERATOR_NOT_MAPPED_TO_SCHEME, response.getErrorCode());
        verify(telemetryTenantRepository, never()).getOrCreatePlaceholderScheme(anyString(), any(), any());
        verify(bfmReadingService, never()).createReading(any(), anyString(), any(), anyString(), anyBoolean());
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlificImageWorkflowService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlificImageWorkflowService.class);
        logger.detachAppender(appender);
    }

    private boolean infoLogged(ListAppender<ILoggingEvent> appender, String fragment) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .anyMatch(event -> event.getFormattedMessage().contains(fragment));
    }
}
