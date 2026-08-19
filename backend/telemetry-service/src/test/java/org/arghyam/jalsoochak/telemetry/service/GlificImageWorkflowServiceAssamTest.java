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
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
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
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
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
        verify(bfmReadingService).createReading(
                any(CreateReadingRequest.class),
                anyString(),
                any(),
                anyString(),
                anyBoolean(),
                eq(FlowVisionRetryMode.RESILIENT)
        );
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
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
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
        verify(bfmReadingService).createReading(
                any(CreateReadingRequest.class),
                anyString(),
                any(),
                anyString(),
                anyBoolean(),
                eq(FlowVisionRetryMode.RESILIENT)
        );
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
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
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
    void processAssamReadingFlagsSchemeIdMismatchWhenMatchedSchemeIsReal() {
        // SCHEME-ID-MISMATCH: the reading matched on the state id and resolved to a real scheme; the
        // other (centre) id must be cross-checked so a wrong master id can be reconciled later. The raw
        // submitted ids are handed to the repository, which decides whether they actually disagree.
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
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_assam", 11L, 30178236L)).thenReturn(true);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        verify(telemetryTenantRepository).recordSchemeIdMismatchIfAny(
                "tenant_assam", 30178236L, "30178236", "30244993");
    }

    @Test
    void processAssamReadingDoesNotFlagMismatchForAutoProvisionedPlaceholder() {
        // SCHEME-ID-MISMATCH: when both ids are unknown the reading lands on an auto-provisioned
        // placeholder scheme; there is no master row to reconcile against, so nothing must be flagged.
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
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "99999999"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId("tenant_assam", "88888888"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.getOrCreatePlaceholderScheme("tenant_assam", "99999999", "88888888"))
                .thenReturn(55555L);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        verify(telemetryTenantRepository, never()).recordSchemeIdMismatchIfAny(
                anyString(), any(), anyString(), anyString());
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
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
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
        verify(bfmReadingService).createReading(
                requestCaptor.capture(),
                anyString(),
                any(),
                anyString(),
                anyBoolean(),
                eq(FlowVisionRetryMode.RESILIENT)
        );
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
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
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
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class));
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
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
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
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class));
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
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
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
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class));
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
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
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
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class));
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
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class)))
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
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean(), any(FlowVisionRetryMode.class));
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

    @Test
    void processAssamReadingWithoutPhoneCreditsFirstPumpOperatorOfScheme() {
        // PHONE-OPTIONAL: no phone in the payload -> the scheme is resolved first and the reading is
        // credited to the pump operator mapped to it, tagged PHONE_ABSENT so the inferred operator is
        // never mistaken for the actual submitter.
        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .centreSchemeId("30244993")
                .phoneNumber(null)
                .build();

        TelemetryOperator pumpOperator =
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null);
        TelemetryOperatorWithSchema inferred = new TelemetryOperatorWithSchema("tenant_assam", pumpOperator);

        when(telemetryTenantRepository.findSchemaNameByTenantId(22)).thenReturn(Optional.of("tenant_assam"));
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.findFirstPumpOperatorForScheme("tenant_assam", 30178236L))
                .thenReturn(Optional.of(pumpOperator));
        when(operatorContextService.resolveOperatorLanguage(inferred, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), nullable(String.class), anyBoolean(), any(FlowVisionRetryMode.class)))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

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
        // The credited operator's own phone is passed on so the reading keeps that operator's channel.
        verify(bfmReadingService).createReading(
                requestCaptor.capture(),
                eq("tenant_assam"),
                eq(pumpOperator),
                eq("919876543210"),
                anyBoolean(),
                eq(FlowVisionRetryMode.RESILIENT));
        CreateReadingRequest captured = requestCaptor.getValue();
        assertEquals(30178236L, captured.getSchemeId());
        assertEquals(11L, captured.getOperatorId());
        assertEquals(IngestionSource.PHONE_ABSENT, captured.getIngestionSource(),
                "Only PHONE_ABSENT should be set when the inferred operator is a real, mapped operator");
        assertNull(captured.getSubmittedPhoneHash());
        // Nothing was submitted to hash.
        verify(telemetryTenantRepository, never()).hashSubmittedPhone(anyString());
        assertTrue(infoLogged(appender, "reason=\"operator_inferred_from_scheme\""));
    }

    @Test
    void processAssamReadingTreatsBlankPhoneAsAbsent() {
        // A blank phone carries no more information than a missing one, so it takes the same path.
        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .phoneNumber("   ")
                .build();

        TelemetryOperator pumpOperator =
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null);
        TelemetryOperatorWithSchema inferred = new TelemetryOperatorWithSchema("tenant_assam", pumpOperator);

        when(telemetryTenantRepository.findSchemaNameByTenantId(22)).thenReturn(Optional.of("tenant_assam"));
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.findFirstPumpOperatorForScheme("tenant_assam", 30178236L))
                .thenReturn(Optional.of(pumpOperator));
        when(operatorContextService.resolveOperatorLanguage(inferred, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), nullable(String.class), anyBoolean(), any(FlowVisionRetryMode.class)))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertTrue(response.isSuccess());

        ArgumentCaptor<CreateReadingRequest> requestCaptor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), nullable(String.class), anyBoolean(), any(FlowVisionRetryMode.class));
        assertEquals(IngestionSource.PHONE_ABSENT, requestCaptor.getValue().getIngestionSource());
        // The blank phone must never be resolved as a contact.
        verify(operatorContextService, never()).tryResolveOperatorWithSchema(anyString(), any());
    }

    @Test
    void processAssamReadingWithoutPhoneFallsBackToSentinelWhenSchemeHasNoPumpOperator() {
        // PHONE-OPTIONAL: a scheme with no mapped pump operator has nobody to credit, so the reading is
        // recorded against the tenant sentinel and tagged PHONE_ABSENT | UNKNOWN_OPERATOR.
        ReflectionTestUtils.setField(service, "lenientIngestionEnabled", true);

        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .build();

        TelemetryOperator sentinel = new TelemetryOperator(
                999L, 22, "Unknown Operator", "unknown-operator@auto.jalsoochak.invalid", "UNKNOWN", null);
        TelemetryOperatorWithSchema sentinelWithSchema = new TelemetryOperatorWithSchema("tenant_assam", sentinel);

        when(telemetryTenantRepository.findSchemaNameByTenantId(22)).thenReturn(Optional.of("tenant_assam"));
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.findFirstPumpOperatorForScheme("tenant_assam", 30178236L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.getOrCreateUnknownOperatorUserId("tenant_assam", 22)).thenReturn(999L);
        when(telemetryTenantRepository.findOperatorById("tenant_assam", 999L)).thenReturn(Optional.of(sentinel));
        when(operatorContextService.resolveOperatorLanguage(sentinelWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), nullable(String.class), anyBoolean(), any(FlowVisionRetryMode.class)))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

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
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), nullable(String.class), anyBoolean(), any(FlowVisionRetryMode.class));
        CreateReadingRequest captured = requestCaptor.getValue();
        assertEquals(999L, captured.getOperatorId());
        assertEquals(IngestionSource.PHONE_ABSENT | IngestionSource.UNKNOWN_OPERATOR, captured.getIngestionSource());
        assertNull(captured.getSubmittedPhoneHash());
        assertTrue(infoLogged(appender, "reason=\"no_operator_mapped_to_scheme\""));
        assertTrue(infoLogged(appender, "phoneAbsent=true"));
    }

    @Test
    void processAssamReadingWithoutPhoneRecordsAgainstPlaceholderWhenSchemeIdsUnknown() {
        // PHONE-OPTIONAL + LENIENT-INGEST: neither the scheme nor a submitter is known, so the reading
        // lands on an auto-provisioned placeholder credited to the sentinel, carrying all three bits.
        ReflectionTestUtils.setField(service, "lenientIngestionEnabled", true);

        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("99999999")
                .centreSchemeId("88888888")
                .build();

        TelemetryOperator sentinel = new TelemetryOperator(
                999L, 22, "Unknown Operator", "unknown-operator@auto.jalsoochak.invalid", "UNKNOWN", null);
        TelemetryOperatorWithSchema sentinelWithSchema = new TelemetryOperatorWithSchema("tenant_assam", sentinel);

        when(telemetryTenantRepository.findSchemaNameByTenantId(22)).thenReturn(Optional.of("tenant_assam"));
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "99999999"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId("tenant_assam", "88888888"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.getOrCreatePlaceholderScheme("tenant_assam", "99999999", "88888888"))
                .thenReturn(55555L);
        when(telemetryTenantRepository.findFirstPumpOperatorForScheme("tenant_assam", 55555L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.getOrCreateUnknownOperatorUserId("tenant_assam", 22)).thenReturn(999L);
        when(telemetryTenantRepository.findOperatorById("tenant_assam", 999L)).thenReturn(Optional.of(sentinel));
        when(operatorContextService.resolveOperatorLanguage(sentinelWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), nullable(String.class), anyBoolean(), any(FlowVisionRetryMode.class)))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertTrue(response.isSuccess());

        ArgumentCaptor<CreateReadingRequest> requestCaptor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), nullable(String.class), anyBoolean(), any(FlowVisionRetryMode.class));
        CreateReadingRequest captured = requestCaptor.getValue();
        assertEquals(55555L, captured.getSchemeId());
        assertEquals(
                IngestionSource.PHONE_ABSENT | IngestionSource.UNKNOWN_SCHEME | IngestionSource.UNKNOWN_OPERATOR,
                captured.getIngestionSource());
        assertEquals("99999999", captured.getSubmittedStateSchemeId());
        // A placeholder has no master row to reconcile against.
        verify(telemetryTenantRepository, never()).recordSchemeIdMismatchIfAny(anyString(), any(), anyString(), anyString());
    }

    @Test
    void processAssamReadingWithoutPhoneIsRejectedWhenLenientIngestionDisabled() {
        // With the off-switch disabled there is no sentinel to fall back on, so a phone-less submission
        // for a scheme with no mapped pump operator is rejected rather than credited to nobody.
        ReflectionTestUtils.setField(service, "lenientIngestionEnabled", false);

        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .build();

        when(telemetryTenantRepository.findSchemaNameByTenantId(22)).thenReturn(Optional.of("tenant_assam"));
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.findFirstPumpOperatorForScheme("tenant_assam", 30178236L))
                .thenReturn(Optional.empty());
        when(localizationService.resolveLanguageKeyForContact(null)).thenReturn("english");
        when(localizationService.resolveUserFacingErrorMessage(any(), anyString(), anyString()))
                .thenReturn("Reading rejected");

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("REJECTED", response.getQualityStatus());
        assertEquals(TelemetryErrorCode.OPERATOR_NOT_MAPPED_TO_SCHEME, response.getErrorCode());
        verify(telemetryTenantRepository, never()).getOrCreateUnknownOperatorUserId(anyString(), any());
        verify(bfmReadingService, never()).createReading(any(), anyString(), any(), nullable(String.class), anyBoolean(), any(FlowVisionRetryMode.class));
    }

    @Test
    void exceptionErrorCodeMappingsArePinned() {
        assertEquals(
                TelemetryErrorCode.OPERATOR_NOT_MAPPED_TO_SCHEME,
                ReflectionTestUtils.invokeMethod(
                        service,
                        "errorCodeForException",
                        new IllegalStateException("Operator is not mapped to the submitted scheme")
                )
        );
        assertEquals(
                TelemetryErrorCode.SCHEME_NOT_FOUND,
                ReflectionTestUtils.invokeMethod(
                        service,
                        "errorCodeForException",
                        new IllegalStateException("Scheme not found")
                )
        );
        assertEquals(
                TelemetryErrorCode.OPERATOR_NOT_FOUND,
                ReflectionTestUtils.invokeMethod(
                        service,
                        "errorCodeForException",
                        new IllegalStateException("Operator not found")
                )
        );
        assertEquals(
                TelemetryErrorCode.PROCESSING_FAILED,
                ReflectionTestUtils.invokeMethod(
                        service,
                        "errorCodeForException",
                        new IllegalStateException("Unexpected workflow failure")
                )
        );
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
