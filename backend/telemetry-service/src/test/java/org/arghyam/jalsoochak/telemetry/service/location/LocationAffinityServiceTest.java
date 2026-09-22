package org.arghyam.jalsoochak.telemetry.service.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryGeoPoint;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantAnomalyRecord;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.AnomalyConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LocationAffinityService")
class LocationAffinityServiceTest {

    private static final String SCHEMA = "tenant_as";
    private static final Integer TENANT = 1;
    private static final Long OPERATOR = 9L;
    private static final Long SCHEME = 7L;
    private static final Long READING = 4242L;
    private static final String SUBMISSION_CORRELATION = "glific-abc-123";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    private static final double SCHEME_LAT = 26.1445d;
    private static final double SCHEME_LNG = 91.7362d;
    /** ~1201 m from the scheme. */
    private static final BigDecimal FAR_LAT = new BigDecimal("26.1553");
    /** ~111 m from the scheme. */
    private static final BigDecimal NEAR_LAT = new BigDecimal("26.1455");
    private static final BigDecimal LNG = new BigDecimal("91.7362");

    private static final ReadingSubmission SUBMISSION =
            new ReadingSubmission(READING, SUBMISSION_CORRELATION, DAY);

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;

    private LocationAffinityService service;

    @BeforeEach
    void setUp() {
        service = new LocationAffinityService(
                telemetryTenantRepository,
                tenantConfigRepository,
                new ObjectMapper(),
                telemetryEventPublisher,
                new SimpleMeterRegistry());

        checkRequired("{\"value\":\"YES\"}");
        threshold("{\"value\":\"500\"}");
        schemeAt(SCHEME_LAT, SCHEME_LNG);
        when(telemetryTenantRepository.countAnomaliesByTypeForToday(
                anyString(), anyLong(), anyLong(), anyInt())).thenReturn(0);
    }

    private void checkRequired(String rawConfig) {
        when(tenantConfigRepository.findConfigValue(TENANT, "LOCATION_CHECK_REQUIRED"))
                .thenReturn(Optional.ofNullable(rawConfig));
    }

    private void threshold(String rawConfig) {
        when(tenantConfigRepository.findConfigValue(0, "LOCATION_AFFINITY_THRESHOLD"))
                .thenReturn(Optional.ofNullable(rawConfig));
    }

    private void schemeAt(Double latitude, Double longitude) {
        when(telemetryTenantRepository.findSchemeLocation(SCHEMA, SCHEME))
                .thenReturn(Optional.of(new TelemetryGeoPoint(latitude, longitude)));
    }

    private LocationVerdict record(BigDecimal latitude, BigDecimal longitude) {
        return service.recordMismatchIfAny(SCHEMA, TENANT, OPERATOR, SCHEME, SUBMISSION,
                latitude, longitude, LocationAffinityService.Path.STATE_API);
    }

    private static LocationVerdict.SkipReason skipReasonOf(LocationVerdict verdict) {
        assertThat(verdict).isInstanceOf(LocationVerdict.Skipped.class);
        return ((LocationVerdict.Skipped) verdict).reason();
    }

    @Nested
    @DisplayName("the tenant gate")
    class TenantGate {

        @Test
        @DisplayName("a tenant that has not enabled the check is skipped without any lookup")
        void skipsWhenNotRequired() {
            checkRequired("{\"value\":\"NO\"}");

            assertThat(skipReasonOf(record(FAR_LAT, LNG)))
                    .isEqualTo(LocationVerdict.SkipReason.CHECK_NOT_REQUIRED);

            // The gate governs the state-IT API too, per the platform decision: an integrator that
            // sends coordinates for an opted-out tenant gets no anomalies.
            verify(telemetryTenantRepository, never()).findSchemeLocation(anyString(), anyLong());
            verify(tenantConfigRepository, never()).findConfigValue(0, "LOCATION_AFFINITY_THRESHOLD");
            verifyNoInteractions(telemetryEventPublisher);
        }

        @Test
        @DisplayName("an unset flag means not enabled, not enabled-by-default")
        void skipsWhenUnset() {
            checkRequired(null);

            assertThat(skipReasonOf(record(FAR_LAT, LNG)))
                    .isEqualTo(LocationVerdict.SkipReason.CHECK_NOT_REQUIRED);
        }

        @Test
        @DisplayName("a bare string config is honoured as well as the wrapped form")
        void acceptsBareStringFlag() {
            checkRequired("\"YES\"");

            assertThat(record(NEAR_LAT, LNG)).isInstanceOf(LocationVerdict.Within.class);
        }

        @Test
        @DisplayName("the flag is case-insensitive and tolerates padding")
        void toleratesCaseAndPadding() {
            checkRequired("{\"value\":\" yes \"}");

            assertThat(record(NEAR_LAT, LNG)).isInstanceOf(LocationVerdict.Within.class);
        }
    }

    @Nested
    @DisplayName("the threshold")
    class Threshold {

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"value\":\"abc\"}",
                "{\"value\":\"-5\"}",
                "{\"value\":\"0\"}",
                "{\"value\":\"\"}",
                "not json at all"
        })
        @DisplayName("an unusable value skips rather than guessing a radius")
        void unusableThresholdSkips(String raw) {
            threshold(raw);

            assertThat(skipReasonOf(record(FAR_LAT, LNG)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_THRESHOLD);
            verifyNoInteractions(telemetryEventPublisher);
        }

        @Test
        @DisplayName("an absent value skips")
        void absentThresholdSkips() {
            threshold(null);

            assertThat(skipReasonOf(record(FAR_LAT, LNG)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_THRESHOLD);
        }

        @Test
        @DisplayName("is read at tenant_id 0, because it is a system-level setting")
        void isReadFromSystemConfig() {
            record(FAR_LAT, LNG);

            verify(tenantConfigRepository).findConfigValue(0, "LOCATION_AFFINITY_THRESHOLD");
            verify(tenantConfigRepository, never()).findConfigValue(TENANT, "LOCATION_AFFINITY_THRESHOLD");
        }

        @Test
        @DisplayName("a bare string and a thousands separator both parse")
        void parsesLenientForms() {
            threshold("\"1,000\"");

            // 1201 m away, threshold 1000 m.
            assertThat(record(FAR_LAT, LNG)).isInstanceOf(LocationVerdict.Outside.class);
        }

        @Test
        @DisplayName("a config read that blows up is a skip, never a mismatch")
        void configFailureSkips() {
            when(tenantConfigRepository.findConfigValue(0, "LOCATION_AFFINITY_THRESHOLD"))
                    .thenThrow(new IllegalStateException("db down"));

            assertThat(skipReasonOf(record(FAR_LAT, LNG)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_THRESHOLD);
        }

        @Test
        @DisplayName("a literal null Optional from an unstubbed mock does not NPE")
        void nullOptionalIsTolerated() {
            when(tenantConfigRepository.findConfigValue(0, "LOCATION_AFFINITY_THRESHOLD")).thenReturn(null);

            assertThat(skipReasonOf(record(FAR_LAT, LNG)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_THRESHOLD);
        }
    }

    @Nested
    @DisplayName("coordinates")
    class Coordinates {

        @Test
        @DisplayName("a submission with none is skipped")
        void noReadingLocation() {
            when(telemetryTenantRepository.findReadingLocation(SCHEMA, READING))
                    .thenReturn(Optional.of(new TelemetryGeoPoint(null, null)));

            assertThat(skipReasonOf(record(null, null)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_READING_LOCATION);
        }

        @Test
        @DisplayName("a scheme without them is skipped, and the submission is not blamed")
        void noSchemeLocation() {
            schemeAt(null, null);

            assertThat(skipReasonOf(record(FAR_LAT, LNG)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_SCHEME_LOCATION);
            verifyNoInteractions(telemetryEventPublisher);
        }

        @Test
        @DisplayName("a scheme lookup failure is a skip, never a mismatch")
        void schemeLookupFailureSkips() {
            when(telemetryTenantRepository.findSchemeLocation(SCHEMA, SCHEME))
                    .thenThrow(new IllegalStateException("db down"));

            assertThat(skipReasonOf(record(FAR_LAT, LNG)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_SCHEME_LOCATION);
        }

        @Test
        @DisplayName("are read off the reading row when the request did not carry them")
        void fallsBackToTheStoredRow() {
            // THE WhatsApp path: /location wrote these onto the placeholder row minutes earlier and
            // the image submission reused it, so the reading request itself has no coordinates.
            when(telemetryTenantRepository.findReadingLocation(SCHEMA, READING))
                    .thenReturn(Optional.of(new TelemetryGeoPoint(FAR_LAT.doubleValue(), SCHEME_LNG)));

            assertThat(record(null, null)).isInstanceOf(LocationVerdict.Outside.class);
        }

        @Test
        @DisplayName("on the request win over the row, so the state API needs no extra query")
        void requestCoordinatesArePreferred() {
            record(NEAR_LAT, LNG);

            verify(telemetryTenantRepository, never()).findReadingLocation(anyString(), anyLong());
        }
    }

    @Nested
    @DisplayName("recording a mismatch")
    class Recording {

        @Test
        @DisplayName("writes a type-11 tenant row against the submission")
        void writesTheTenantRow() {
            assertThat(record(FAR_LAT, LNG)).isInstanceOf(LocationVerdict.Outside.class);

            ArgumentCaptor<TenantAnomalyRecord> row = ArgumentCaptor.forClass(TenantAnomalyRecord.class);
            verify(telemetryTenantRepository).createTenantAnomalyRecord(eq(SCHEMA), row.capture());

            assertThat(row.getValue().type()).isEqualTo(AnomalyConstants.TYPE_LOCATION_MISMATCH);
            assertThat(row.getValue().status()).isEqualTo(AnomalyConstants.STATUS_OPEN);
            assertThat(row.getValue().reason()).isEqualTo(AnomalyConstants.REASON_LOCATION_MISMATCH);
            assertThat(row.getValue().userId()).isEqualTo(OPERATOR);
            assertThat(row.getValue().schemeId()).isEqualTo(SCHEME);
            // ANOMALY-SUBMISSION-LINK — "captured against this submission" on the operational side.
            assertThat(row.getValue().flowReadingId()).isEqualTo(READING);
        }

        @Test
        @DisplayName("publishes the analytics event with both submission links")
        void publishesTheEvent() {
            record(FAR_LAT, LNG);

            verify(telemetryEventPublisher).publishAnomalyRecorded(
                    eq(TENANT),
                    eq(AnomalyConstants.TYPE_LOCATION_MISMATCH),
                    eq(OPERATOR),
                    eq(SCHEME),
                    isNull(), isNull(), isNull(),
                    eq(0),
                    isNull(), isNull(),
                    eq(0),
                    eq(AnomalyConstants.REASON_LOCATION_MISMATCH),
                    eq(AnomalyConstants.STATUS_OPEN),
                    anyString(),
                    // The warehouse joins fact_meter_reading_table on this, not on flow_reading_id.
                    eq(SUBMISSION_CORRELATION));
        }

        @Test
        @DisplayName("keys the correlation id on operator, scheme and day so a retry does not duplicate")
        void correlationIdIsDeterministic() {
            record(FAR_LAT, LNG);
            record(FAR_LAT, LNG);

            ArgumentCaptor<String> correlationId = ArgumentCaptor.forClass(String.class);
            verify(telemetryEventPublisher, org.mockito.Mockito.times(2)).publishAnomalyRecorded(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), correlationId.capture(), any());

            assertThat(correlationId.getAllValues().get(0))
                    .isEqualTo(correlationId.getAllValues().get(1));
        }

        @Test
        @DisplayName("a different day gets a different correlation id")
        void correlationIdVariesByDay() {
            service.recordMismatchIfAny(SCHEMA, TENANT, OPERATOR, SCHEME,
                    new ReadingSubmission(READING, SUBMISSION_CORRELATION, DAY),
                    FAR_LAT, LNG, LocationAffinityService.Path.STATE_API);
            service.recordMismatchIfAny(SCHEMA, TENANT, OPERATOR, SCHEME,
                    new ReadingSubmission(READING, SUBMISSION_CORRELATION, DAY.plusDays(1)),
                    FAR_LAT, LNG, LocationAffinityService.Path.STATE_API);

            ArgumentCaptor<String> correlationId = ArgumentCaptor.forClass(String.class);
            verify(telemetryEventPublisher, org.mockito.Mockito.times(2)).publishAnomalyRecorded(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), correlationId.capture(), any());

            assertThat(correlationId.getAllValues().get(0))
                    .isNotEqualTo(correlationId.getAllValues().get(1));
        }

        @Test
        @DisplayName("does not write a second tenant row on the same day")
        void tenantRowIsDedupedPerDay() {
            // The tenant table has no correlation_id, so without this an operator retrying a blurry
            // image three times leaves three boundary anomalies on the operational table.
            when(telemetryTenantRepository.countAnomaliesByTypeForToday(
                    SCHEMA, OPERATOR, SCHEME, AnomalyConstants.TYPE_LOCATION_MISMATCH)).thenReturn(1);

            record(FAR_LAT, LNG);

            verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(anyString(), any());
            // The event still goes out: analytics dedups on the derived uuid and touches the row.
            verify(telemetryEventPublisher).publishAnomalyRecorded(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any());
        }

        @Test
        @DisplayName("a failed write never costs the reading")
        void writeFailureIsSwallowed() {
            // createReading is not transactional and has already told the operator the reading was
            // accepted. Throwing here would abandon it over an observation.
            doThrow(new IllegalStateException("insert failed"))
                    .when(telemetryTenantRepository).createTenantAnomalyRecord(anyString(), any());

            assertThat(record(FAR_LAT, LNG)).isInstanceOf(LocationVerdict.Outside.class);
        }

        @Test
        @DisplayName("nothing is recorded for a submission inside the boundary")
        void withinRecordsNothing() {
            assertThat(record(NEAR_LAT, LNG)).isInstanceOf(LocationVerdict.Within.class);

            verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(anyString(), any());
            verifyNoInteractions(telemetryEventPublisher);
        }
    }

    @Nested
    @DisplayName("assess")
    class Assess {

        private LocationVerdict assess(BigDecimal latitude) {
            return service.assess(SCHEMA, TENANT, SCHEME, latitude, LNG,
                    LocationAffinityService.Path.LOCATION_WEBHOOK);
        }

        @Test
        @DisplayName("reports a mismatch without recording one")
        void doesNotRecord() {
            // /location runs before any reading exists. Recording here would leave an anomaly behind
            // for an operator who then answers "No" and walks away.
            assertThat(assess(FAR_LAT)).isInstanceOf(LocationVerdict.Outside.class);

            verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(anyString(), any());
            verifyNoInteractions(telemetryEventPublisher);
        }

        @Test
        @DisplayName("returns within for a submission inside the boundary")
        void withinIsWithin() {
            assertThat(assess(NEAR_LAT)).isInstanceOf(LocationVerdict.Within.class);
        }

        @Test
        @DisplayName("respects the tenant gate like every other path")
        void respectsTheGate() {
            checkRequired("{\"value\":\"NO\"}");

            assertThat(skipReasonOf(assess(FAR_LAT)))
                    .isEqualTo(LocationVerdict.SkipReason.CHECK_NOT_REQUIRED);
        }
    }
}
