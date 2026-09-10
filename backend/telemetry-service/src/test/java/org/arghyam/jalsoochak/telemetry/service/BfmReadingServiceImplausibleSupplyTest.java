package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSupplyCounts;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantAnomalyRecord;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.water.QuarantineReason;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SUPPLY-PLAUSIBILITY on {@code POST /readings}.
 *
 * <p>Worked example throughout: 100 FHTC x 5 persons = 500 people, ceiling 500 x 150 = 75,000 L/day,
 * baseline 900 m&sup3;. So 950 (50,000 L) passes and 1100 (200,000 L) does not.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BfmReadingService — implausible supply on submission")
class BfmReadingServiceImplausibleSupplyTest {

    private static final String SCHEMA = "tenant_as";
    private static final long SCHEME_ID = 10L;
    private static final long OPERATOR_ID = 7L;
    private static final int TENANT_ID = 1;
    private static final String CONTACT = "919999999999";
    private static final long READING_ID = 99L;
    private static final LocalDateTime READING_AT = LocalDateTime.of(2026, 9, 8, 9, 30);
    private static final BigDecimal BASELINE = new BigDecimal("900");
    private static final LocalDateTime BASELINE_AT = LocalDateTime.of(2026, 9, 7, 9, 0);

    @Mock
    private TelemetryTenantRepository repo;
    @Mock
    private FlowVisionService flowVisionService;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private GlificOperatorContextService glificOperatorContextService;
    @Mock
    private ReadingChannelResolver readingChannelResolver;

    private final TelemetryOperator operator =
            new TelemetryOperator(OPERATOR_ID, TENANT_ID, "op", "op@example.com", CONTACT, null);

    @BeforeEach
    void setUp() {
        lenient().when(repo.existsSchemeById(SCHEMA, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findOperatorById(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(operator));
        lenient().when(repo.isOperatorMappedToScheme(SCHEMA, OPERATOR_ID, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME_ID, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(BASELINE, BASELINE_AT)));
        lenient().when(readingChannelResolver.resolve(any(), any())).thenReturn(ReadingChannel.BFM);
        // createReading reads two tenant configs on every submission for a block that is commented
        // out; answer them all as absent so the household size falls back to the configured default.
        lenient().when(tenantConfigRepository.findConfigValue(anyInt(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(repo.findLatestPlaceholderFlowReadingIdForDate(
                eq(SCHEMA), eq(SCHEME_ID), eq(OPERATOR_ID), any(LocalDate.class))).thenReturn(Optional.empty());
        lenient().when(repo.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                any(), any(), any(), anyInt(), any(), any(), any(), any(), any())).thenReturn(READING_ID);
        lenient().when(repo.createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                any(BigDecimal.class), any(BigDecimal.class), anyString(), any(), any(), any()))
                .thenReturn(READING_ID);
    }

    private BfmReadingService service(SupplyPlausibilityProperties.Mode mode) {
        return new BfmReadingService(
                repo, flowVisionService, telemetryEventPublisher, tenantConfigRepository,
                new ObjectMapper(), glificOperatorContextService, null, readingChannelResolver,
                new RolloverResolutionService(false, new ObjectMapper()),
                SupplyPlausibilityFixtures.guard(mode, repo, tenantConfigRepository));
    }

    /** The scheme is migrated, has 100 connections, and has a reading to be measured against. */
    private void checkableScheme() {
        when(repo.supportsQuarantine(SCHEMA)).thenReturn(true);
        when(repo.findLatestConfirmedReadingSnapshotBeforeDate(
                eq(SCHEMA), eq(SCHEME_ID), any(LocalDate.class), isNull()))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(BASELINE, BASELINE_AT)));
        when(repo.findSchemeSupplyCounts(SCHEMA, SCHEME_ID))
                .thenReturn(Optional.of(new TelemetrySchemeSupplyCounts(100, 0, 0)));
    }

    private CreateReadingRequest submission(String value, boolean checked) {
        return CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingValue(new BigDecimal(value))
                .externallyAsserted(true)
                .supplyPlausibilityChecked(checked)
                .readingTime(READING_AT)
                .build();
    }

    private CreateReadingResponse submit(SupplyPlausibilityProperties.Mode mode, String value, boolean checked) {
        return service(mode).createReading(submission(value, checked), SCHEMA, operator, CONTACT, false);
    }

    @Nested
    @DisplayName("ENFORCE")
    class Enforce {

        @Test
        @DisplayName("stores the submission with the quarantine marker instead of discarding it")
        void storesQuarantined() {
            checkableScheme();

            submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", true);

            verify(repo).persistFlowReadingWithTracking(eq(SCHEMA), isNull(), eq(SCHEME_ID), eq(OPERATOR_ID),
                    any(LocalDateTime.class), any(BigDecimal.class), eq(new BigDecimal("1100")), anyString(),
                    isNull(), isNull(), isNull(), eq(IngestionSource.NORMAL), isNull(), isNull(), isNull(),
                    eq(RolloverResolutionService.SOURCE_EXTERNALLY_ASSERTED),
                    eq(QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY));
        }

        @Test
        @DisplayName("withholds the reading from the warehouse")
        void withholdsFromAnalytics() {
            checkableScheme();

            submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", true);

            // publishMeterReadingRecorded is the single event that writes fact_meter_reading,
            // dim_operator_attendance and fact_water_quantity. Withholding it is the whole point.
            verify(telemetryEventPublisher, never()).publishMeterReadingRecorded(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("still writes the channel — the row is quarantined, not unrecorded")
        void stillWritesChannel() {
            checkableScheme();

            submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", true);

            verify(repo).updateFlowReadingChannel(SCHEMA, READING_ID, ReadingChannel.BFM.name());
        }

        @Test
        @DisplayName("records the internal anomaly while answering with the vaguer wire code")
        void internalAndWireNamesStayDistinct() {
            checkableScheme();

            CreateReadingResponse response = submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", true);

            // Asserted together on purpose: the internal anomaly names the condition for staff, the
            // wire code deliberately does not. If someone unifies the two names, this fails.
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getQualityStatus()).isEqualTo("REJECTED");
            assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
            verify(repo).createTenantAnomalyRecord(eq(SCHEMA), argThat(anomaly ->
                    anomaly.userId() == OPERATOR_ID
                            && anomaly.schemeId() == SCHEME_ID
                            && anomaly.type() == AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY
                            && AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_SUBMITTED.equals(anomaly.reason())
                            && anomaly.status() == AnomalyConstants.STATUS_OPEN));
        }

        @Test
        @DisplayName("the anomaly's previousReading is the baseline, so the litres are recomputable")
        void anomalyCarriesTheBaseline() {
            checkableScheme();

            submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", true);

            ArgumentCaptor<BigDecimal> overridden = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<BigDecimal> previous = ArgumentCaptor.forClass(BigDecimal.class);
            verify(telemetryEventPublisher).publishAnomalyRecorded(
                    eq(TENANT_ID), eq(AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY), eq(OPERATOR_ID),
                    eq(SCHEME_ID), isNull(), isNull(), overridden.capture(), eq(0), previous.capture(),
                    eq(BASELINE_AT), eq(0), eq(AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_SUBMITTED),
                    eq(AnomalyConstants.STATUS_OPEN), anyString());

            assertThat(overridden.getValue()).isEqualByComparingTo("1100");
            assertThat(previous.getValue()).isEqualByComparingTo(BASELINE);
            // (1100 - 900) * 1000 = 200,000 L, the figure the decision was taken on.
            assertThat(overridden.getValue().subtract(previous.getValue())
                    .multiply(BigDecimal.valueOf(1000))).isEqualByComparingTo("200000");
        }

        /**
         * The tenant row has to carry the same numbers as the event. It did not once: the insert
         * wrote only the identifying columns, so staff querying the tenant schema saw the anomaly
         * with no way to tell what value had been refused or what it was measured against.
         */
        @Test
        @DisplayName("the tenant row carries the same numbers as the event")
        void tenantRowCarriesTheSameNumbers() {
            checkableScheme();

            submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", true);

            ArgumentCaptor<TenantAnomalyRecord> anomaly = ArgumentCaptor.forClass(TenantAnomalyRecord.class);
            verify(repo).createTenantAnomalyRecord(eq(SCHEMA), anomaly.capture());

            assertThat(anomaly.getValue().overriddenReading()).isEqualByComparingTo("1100");
            assertThat(anomaly.getValue().previousReading()).isEqualByComparingTo(BASELINE);
            assertThat(anomaly.getValue().previousReadingDate()).isEqualTo(BASELINE_AT);
        }

        @Test
        @DisplayName("the response discloses no ceiling, population or connection count")
        void responseDisclosesNoThreshold() {
            checkableScheme();

            CreateReadingResponse response = submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", true);

            // 75000 = the ceiling, 500 = the population, 100 = the FHTC count, 150 = the per-person
            // limit. Any of them in the body would let a caller solve for the rest.
            assertThat(response.getMessage())
                    .isEqualTo("Reading rejected: this reading looks unusually high for this scheme. "
                            + "Please check the meter reading and try again.")
                    .doesNotContain("75000", "500", "100", "150");
        }

        @Test
        @DisplayName("a plausible submission is unaffected")
        void plausibleSubmissionPasses() {
            checkableScheme();

            CreateReadingResponse response = submit(SupplyPlausibilityProperties.Mode.ENFORCE, "950", true);

            assertThat(response.isSuccess()).isTrue();
            verify(repo).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                    any(LocalDateTime.class), any(BigDecimal.class), eq(new BigDecimal("950")), anyString(),
                    any(), any(), any(), anyInt(), any(), any(), any(), any(), isNull());
            verify(repo, never()).createTenantAnomalyRecord(anyString(), any());
        }

        @Test
        @DisplayName("an image submission is routed through the transactional insert so the marker "
                + "cannot land separately from the row")
        void imageSubmissionTakesTheTransactionalPath() {
            checkableScheme();
            when(flowVisionService.extractReading(anyString())).thenReturn(FlowVisionResult.builder()
                    .adjustedReading(new BigDecimal("1100"))
                    .qualityConfidence(new BigDecimal("0.95"))
                    .build());

            CreateReadingRequest request = CreateReadingRequest.builder()
                    .schemeId(SCHEME_ID)
                    .operatorId(OPERATOR_ID)
                    .readingUrl("https://example.test/meter.jpg")
                    .supplyPlausibilityChecked(true)
                    .readingTime(READING_AT)
                    .build();

            service(SupplyPlausibilityProperties.Mode.ENFORCE)
                    .createReading(request, SCHEMA, operator, CONTACT, false);

            // Without the widened branch this would have gone to createFlowReading, which has no
            // way to carry the marker.
            verify(repo).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                    any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                    any(), any(), any(), anyInt(), any(), any(), any(), any(),
                    eq(QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY));
            verify(repo, never()).createFlowReading(anyString(), anyLong(), anyLong(),
                    any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                    any(), any(), any());
        }
    }

    @Nested
    @DisplayName("AUDIT")
    class Audit {

        @Test
        @DisplayName("serves the same implausible reading normally")
        void servesNormally() {
            checkableScheme();

            CreateReadingResponse response = submit(SupplyPlausibilityProperties.Mode.AUDIT, "1100", true);

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getErrorCode()).isNull();
        }

        @Test
        @DisplayName("publishes it to the warehouse and stores it unmarked")
        void publishesAndStoresUnmarked() {
            checkableScheme();

            submit(SupplyPlausibilityProperties.Mode.AUDIT, "1100", true);

            verify(telemetryEventPublisher).publishMeterReadingRecorded(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(repo).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                    any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(),
                    any(), any(), any(), anyInt(), any(), any(), any(), any(), isNull());
        }

        @Test
        @DisplayName("records no anomaly — the audit period sizes the problem, it does not queue work")
        void recordsNoAnomaly() {
            checkableScheme();

            submit(SupplyPlausibilityProperties.Mode.AUDIT, "1100", true);

            verify(repo, never()).createTenantAnomalyRecord(anyString(), any());
            verify(telemetryEventPublisher, never()).publishAnomalyRecorded(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any());
        }
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("a caller that did not opt in is never checked, so the Glific path is untouched")
        void unopposedCallerIsNotChecked() {
            // No checkableScheme(): under strict stubs, consulting the scheme's counts would fail.
            CreateReadingResponse response = submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", false);

            assertThat(response.isSuccess()).isTrue();
            verify(telemetryEventPublisher).publishMeterReadingRecorded(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("mode OFF skips before any input is gathered")
        void offSkipsEntirely() {
            CreateReadingResponse response = submit(SupplyPlausibilityProperties.Mode.OFF, "1100", true);

            assertThat(response.isSuccess()).isTrue();
            verify(repo, never()).supportsQuarantine(anyString());
        }

        @Test
        @DisplayName("a pre-V40 tenant is skipped rather than marked, since it has no column to mark")
        void preMigrationTenantIsSkipped() {
            when(repo.supportsQuarantine(SCHEMA)).thenReturn(false);

            CreateReadingResponse response = submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", true);

            assertThat(response.isSuccess()).isTrue();
            verify(repo, never()).findSchemeSupplyCounts(anyString(), anyLong());
        }

        @Test
        @DisplayName("a meter replacement is skipped — the delta across a swap is meaningless")
        void meterReplacementIsSkipped() {
            CreateReadingResponse response = service(SupplyPlausibilityProperties.Mode.ENFORCE)
                    .createReading(submission("1100", true), SCHEMA, operator, CONTACT, true);

            assertThat(response.isSuccess()).isTrue();
            verify(repo, never()).findSchemeSupplyCounts(anyString(), anyLong());
        }

        @Test
        @DisplayName("a non-BFM channel is skipped — only BFM readings are cumulative m3 indices")
        void nonBfmChannelIsSkipped() {
            when(readingChannelResolver.resolve(any(), any())).thenReturn(ReadingChannel.PDU);

            CreateReadingResponse response = submit(SupplyPlausibilityProperties.Mode.ENFORCE, "1100", true);

            assertThat(response.isSuccess()).isTrue();
            verify(repo, never()).findSchemeSupplyCounts(anyString(), anyLong());
        }

        @Test
        @DisplayName("a scheme's first reading is accepted — there is no baseline to derive a volume")
        void firstReadingIsAccepted() {
            when(repo.supportsQuarantine(SCHEMA)).thenReturn(true);
            when(repo.findLatestConfirmedReadingSnapshotBeforeDate(
                    eq(SCHEMA), eq(SCHEME_ID), any(LocalDate.class), isNull())).thenReturn(Optional.empty());

            CreateReadingResponse response = submit(SupplyPlausibilityProperties.Mode.ENFORCE, "99999", true);

            assertThat(response.isSuccess()).isTrue();
        }
    }
}
