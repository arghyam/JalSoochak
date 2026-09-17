package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.dto.requests.UpdateYesterdayFinalReadingBySchemeRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.UpdateYesterdayFinalReadingBySchemeResponse;
import org.arghyam.jalsoochak.telemetry.service.BfmReadingService;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySchemeReadingService;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySubmissionAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authentication on {@code PATCH /api/v1/telemetry/schemes/{schemeId}/yesterday-final-reading}.
 *
 * <p>This endpoint previously had no credential parameter at all: it took its tenant solely from the
 * unauthenticated {@code X-Tenant-Code} header, so a caller chose the tenant, the scheme, the operator
 * and the reading value — and the write republishes water-quantity events to analytics.
 *
 * <p>{@code TelemetryApiKeyAuthFilter} is now the gate and rejects an unauthenticated request before
 * it reaches this handler ({@code TelemetryApiKeyAuthFilterTest} covers that). These tests cover what
 * the filter cannot: the in-handler fallback that still resolves the key when no filter attribute is
 * present, and that the tenant handed to the service is the authenticated one rather than the header's.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SingleTenantTelemetryController — yesterday-final-reading authentication")
class SingleTenantTelemetryControllerPatchAuthTest {

    private static final long SCHEME_ID = 7L;
    private static final String PHONE = "919999900001";
    private static final int TENANT_ID = 22;

    @Mock
    private GlificWebhookService glificWebhookService;
    @Mock
    private BfmReadingService bfmReadingService;
    @Mock
    private TelemetryApiKeyService telemetryApiKeyService;
    @Mock
    private TelemetrySchemeReadingService telemetrySchemeReadingService;
    @Mock
    private TelemetrySubmissionAuditService auditService;

    private SingleTenantTelemetryController controller;

    @BeforeEach
    void setUp() {
        controller = new SingleTenantTelemetryController(
                glificWebhookService,
                telemetryApiKeyService,
                bfmReadingService,
                telemetrySchemeReadingService,
                auditService);
        when(auditService.captureForPhoneAndScheme(any(), any()))
                .thenReturn(new TelemetrySubmissionAuditService.SubmissionAuditSnapshot(
                        "****0001", SCHEME_ID, TENANT_ID, LocalDate.of(2026, 3, 1)));
    }

    private static UpdateYesterdayFinalReadingBySchemeRequest request() {
        return UpdateYesterdayFinalReadingBySchemeRequest.builder()
                .phoneNumber(PHONE)
                .reading(new BigDecimal("600"))
                .build();
    }

    /** No filter attribute: the in-handler fallback resolves the key itself (defence in depth). */
    private ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> invoke(String apiKey) {
        return invoke(apiKey, null);
    }

    private ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> invoke(String apiKey,
                                                                              Integer authenticatedTenantId) {
        return controller.updateYesterdayFinalReadingByScheme(
                apiKey, authenticatedTenantId, SCHEME_ID, null, request());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "js_unknown_key"})
    @DisplayName("rejects a missing, blank or unknown API key with 401")
    void rejectsUnauthenticatedCallers(String apiKey) {
        when(telemetryApiKeyService.resolveTenantIdFromRawApiKey(any())).thenReturn(Optional.empty());

        ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> response = invoke(apiKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"js_unknown_key"})
    @DisplayName("never reaches the write path without a valid key")
    void neverWritesWithoutValidKey(String apiKey) {
        when(telemetryApiKeyService.resolveTenantIdFromRawApiKey(any())).thenReturn(Optional.empty());

        invoke(apiKey);

        verify(telemetrySchemeReadingService, never())
                .updateYesterdayFinalReadingBySchemeId(anyLong(), anyString(), any(), any(), any());
        verify(telemetrySchemeReadingService, never())
                .updateYesterdayFinalReadingBySchemeId(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("passes the tenant resolved from the API key, not the X-Tenant-Code header")
    void passesResolvedTenantToService() {
        when(telemetryApiKeyService.resolveTenantIdFromRawApiKey("js_valid_key"))
                .thenReturn(Optional.of(TENANT_ID));
        when(telemetrySchemeReadingService.updateYesterdayFinalReadingBySchemeId(
                anyLong(), anyString(), any(), any(), any()))
                .thenReturn(UpdateYesterdayFinalReadingBySchemeResponse.builder()
                        .success(true)
                        .schemeId(SCHEME_ID)
                        .message("updated")
                        .build());

        ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> response = invoke("js_valid_key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        verify(telemetrySchemeReadingService).updateYesterdayFinalReadingBySchemeId(
                eq(SCHEME_ID), eq(PHONE), eq(new BigDecimal("600")), eq((LocalDate) null), eq(TENANT_ID));
    }

    @Test
    @DisplayName("authenticates before doing any work, so an invalid key cannot probe the tenant")
    void authenticatesBeforeAnyWork() {
        when(telemetryApiKeyService.resolveTenantIdFromRawApiKey(any())).thenReturn(Optional.empty());

        invoke("js_unknown_key");

        verify(telemetryApiKeyService).resolveTenantIdFromRawApiKey("js_unknown_key");
        verify(telemetrySchemeReadingService, never())
                .updateYesterdayFinalReadingBySchemeId(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("uses the tenant TelemetryApiKeyAuthFilter already resolved, without re-resolving")
    void prefersFilterResolvedTenant() {
        when(telemetrySchemeReadingService.updateYesterdayFinalReadingBySchemeId(
                anyLong(), anyString(), any(), any(), any()))
                .thenReturn(UpdateYesterdayFinalReadingBySchemeResponse.builder()
                        .success(true)
                        .schemeId(SCHEME_ID)
                        .build());

        ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> response = invoke(null, TENANT_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(telemetrySchemeReadingService).updateYesterdayFinalReadingBySchemeId(
                eq(SCHEME_ID), eq(PHONE), any(), any(), eq(TENANT_ID));
        verify(telemetryApiKeyService, never()).resolveTenantIdFromRawApiKey(any());
    }

    @Test
    @DisplayName("does not leak the operator phone number in the response")
    void doesNotEchoPhoneNumber() {
        when(telemetryApiKeyService.resolveTenantIdFromRawApiKey(any())).thenReturn(Optional.empty());

        ResponseEntity<UpdateYesterdayFinalReadingBySchemeResponse> response = invoke("js_unknown_key");

        assertThat(response.getBody()).isNotNull();
        assertThat(String.valueOf(response.getBody().getMessage())).doesNotContain(PHONE);
    }
}
