package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.LocationReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.arghyam.jalsoochak.telemetry.service.location.LocationAffinityService;
import org.arghyam.jalsoochak.telemetry.service.location.LocationVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LOCATION-AFFINITY on {@code POST /location}.
 *
 * <p>Two things are pinned here. The webhook must <strong>report</strong> a boundary overshoot
 * without <strong>recording</strong> one — no reading exists yet, and an operator who sees the
 * warning and cancels must leave nothing behind. And a mismatch must not look like a failure:
 * {@code success} stays true and the coordinates are still written, because they were.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificMeterWorkflowService — /location")
class GlificMeterWorkflowServiceLocationTest {

    private static final String SCHEMA = "tenant_test";
    private static final String PHONE = "919999999999";
    private static final Integer TENANT = 1;
    private static final Long OPERATOR = 1L;
    private static final Long SCHEME = 7L;
    private static final Long READING = 4242L;

    private static final BigDecimal LAT = new BigDecimal("26.1553");
    private static final BigDecimal LNG = new BigDecimal("91.7362");

    @Mock
    private GlificOperatorContextService operatorContextService;
    @Mock
    private GlificLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private GlificMessageTemplatesService templatesService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private LocationAffinityService locationAffinityService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private GlificMeterWorkflowService service;

    @BeforeEach
    void setUp() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                SCHEMA, new TelemetryOperator(OPERATOR, TENANT, "op", "op@example.com", PHONE, null));

        when(operatorContextService.resolveOperatorWithSchema(eq(PHONE), any()))
                .thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, TENANT)).thenReturn("en");
        when(localizationService.normalizeLanguageKey(anyString())).thenReturn("english");
        when(localizationService.localizeMessage(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, OPERATOR))
                .thenReturn(Optional.of(SCHEME));
        when(telemetryTenantRepository.findLatestFlowReadingForDate(anyString(), anyLong(), anyLong(), any()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.createFlowReading(
                anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(READING);
        when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());
    }

    private void verdict(LocationVerdict verdict) {
        when(locationAffinityService.assess(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(verdict);
    }

    private CreateReadingResponse post() {
        return service.locationReadingMessage(LocationReadingRequest.builder()
                .latitude(LAT)
                .longitude(LNG)
                .contact(LocationReadingRequest.Contact.builder().phone(PHONE).build())
                .build());
    }

    @Test
    @DisplayName("flags a submission outside the boundary so the flow can branch")
    void flagsAMismatch() {
        verdict(new LocationVerdict.Outside(1201.0d, 500.0d));

        CreateReadingResponse response = post();

        assertThat(response.isLocationMismatch()).isTrue();
        // Not a failure: the coordinates were saved. The flow branches on locationMismatch, never
        // on success, and qualityStatus is left alone because the live flow may already route on it.
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getQualityStatus()).isEqualTo("CONFIRMED");
        assertThat(response.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("returns the boundary warning as the message, so the flow need not hard-code it")
    void returnsTheLocalizedWarning() {
        verdict(new LocationVerdict.Outside(1201.0d, 500.0d));

        assertThat(post().getMessage())
                .isEqualTo("System detected that reading is being submitted outside the Scheme "
                        + "boundary. Do you want to proceed?");
    }

    @Test
    @DisplayName("prefers the tenant's configured wording over the English floor")
    void prefersTheConfiguredWording() {
        verdict(new LocationVerdict.Outside(1201.0d, 500.0d));
        when(templatesService.resolveScreenMessage(TENANT, "LOCATION_BOUNDARY", "english"))
                .thenReturn(Optional.of("आप योजना की सीमा के बाहर हैं। क्या आप आगे बढ़ना चाहते हैं?"));

        assertThat(post().getMessage())
                .isEqualTo("आप योजना की सीमा के बाहर हैं। क्या आप आगे बढ़ना चाहते हैं?");
    }

    @Test
    @DisplayName("falls back to the legacy per-language config row")
    void fallsBackToLegacyConfigKey() {
        verdict(new LocationVerdict.Outside(1201.0d, 500.0d));
        when(tenantConfigRepository.findConfigValue(TENANT, "location_boundary_warning_english"))
                .thenReturn(Optional.of("Outside the scheme boundary. Continue?"));

        assertThat(post().getMessage()).isEqualTo("Outside the scheme boundary. Continue?");
    }

    @Test
    @DisplayName("a submission inside the boundary is unchanged from before this feature")
    void withinIsUnchanged() {
        verdict(new LocationVerdict.Within(111.0d, 500.0d));

        CreateReadingResponse response = post();

        assertThat(response.isLocationMismatch()).isFalse();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Location saved successfully.");
    }

    @Test
    @DisplayName("a skipped check reads as no mismatch")
    void skippedIsNoMismatch() {
        verdict(new LocationVerdict.Skipped(LocationVerdict.SkipReason.CHECK_NOT_REQUIRED));

        assertThat(post().isLocationMismatch()).isFalse();
    }

    @Test
    @DisplayName("evaluates without recording, so cancelling leaves no anomaly behind")
    void doesNotRecordAnAnomaly() {
        verdict(new LocationVerdict.Outside(1201.0d, 500.0d));

        post();

        verify(locationAffinityService).assess(eq(SCHEMA), eq(TENANT), eq(SCHEME), eq(LAT), eq(LNG),
                eq(LocationAffinityService.Path.LOCATION_WEBHOOK));
        verify(locationAffinityService, never())
                .recordMismatchIfAny(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("still writes the coordinates, and writes them before assessing")
    void storesTheLocationFirst() {
        verdict(new LocationVerdict.Outside(1201.0d, 500.0d));

        post();

        // The row has to carry the coordinates whatever the verdict: the deferred check that
        // records the anomaly later reads them back off this row.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                telemetryTenantRepository, locationAffinityService);
        order.verify(telemetryTenantRepository)
                .updateReadingLocation(SCHEMA, READING, LAT, LNG, OPERATOR);
        order.verify(locationAffinityService).assess(anyString(), any(), any(), any(), any(), any());
    }
}
