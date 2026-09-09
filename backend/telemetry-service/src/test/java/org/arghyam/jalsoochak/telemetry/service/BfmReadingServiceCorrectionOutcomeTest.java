package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SUPPLY-PLAUSIBILITY on {@code PUT /readings} — the correction outcome rule.
 *
 * <p>Worked example throughout: 100 FHTC x 5 persons = 500 people, ceiling 500 x 150 = 75,000 L/day,
 * baseline 900 m&sup3;. So correcting to 950 (50,000 L) passes and correcting to 1100 (200,000 L)
 * does not.
 *
 * <p>The rule under test is that the write decision <strong>does not branch on the target row's
 * quarantine state</strong>: a refused correction writes nothing and an accepted one clears the flag
 * unconditionally, whether the target was published or already quarantined. The row's marker selects
 * the anomaly reason text and nothing else. Several tests below are therefore deliberately
 * parameterised over both target states with a single expectation — if someone reintroduces a
 * state-dependent branch, they fail.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BfmReadingService — implausible supply on correction")
class BfmReadingServiceCorrectionOutcomeTest {

    private static final String SCHEMA = "tenant_as";
    private static final long SCHEME_ID = 10L;
    private static final long OPERATOR_ID = 7L;
    private static final int TENANT_ID = 1;
    private static final String CONTACT = "919999999999";
    private static final String CORRELATION_ID = "corr-1";
    private static final long READING_ID = 99L;
    private static final LocalDate READING_DATE = LocalDate.of(2026, 9, 8);
    private static final LocalDateTime READING_AT = READING_DATE.atTime(9, 30);
    private static final BigDecimal STANDING = new BigDecimal("950");
    private static final BigDecimal BASELINE = new BigDecimal("900");
    private static final LocalDateTime BASELINE_AT = LocalDateTime.of(2026, 9, 7, 9, 0);
    private static final BigDecimal PLAUSIBLE = new BigDecimal("940");
    private static final BigDecimal IMPLAUSIBLE = new BigDecimal("1100");

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
        lenient().when(tenantConfigRepository.findConfigValue(anyInt(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(repo.findOperatorById(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(operator));
    }

    private BfmReadingService service(SupplyPlausibilityProperties.Mode mode) {
        return new BfmReadingService(
                repo, flowVisionService, telemetryEventPublisher, tenantConfigRepository,
                new ObjectMapper(), glificOperatorContextService, null, readingChannelResolver,
                new RolloverResolutionService(false, new ObjectMapper()),
                SupplyPlausibilityFixtures.guard(mode, repo, tenantConfigRepository));
    }

    /** The row a correction resolves to, published (reason 0) or already quarantined (reason 1). */
    private TelemetryLatestFlowReadingRecord target(int quarantineReason) {
        return new TelemetryLatestFlowReadingRecord(
                READING_ID, SCHEME_ID, OPERATOR_ID, CORRELATION_ID,
                new BigDecimal("948"), STANDING, "https://img/1.jpg",
                READING_DATE, READING_AT, ReadingChannel.BFM.name(), quarantineReason);
    }

    /** The scheme is migrated, has 100 connections, and has an earlier reading to measure against. */
    private void checkableScheme(int quarantineReason) {
        when(repo.supportsQuarantine(SCHEMA)).thenReturn(true);
        when(repo.findSchemeSupplyCounts(SCHEMA, SCHEME_ID))
                .thenReturn(Optional.of(new TelemetrySchemeSupplyCounts(100, 0, 0)));
        when(repo.findLatestConfirmedReadingSnapshotBeforeDate(
                SCHEMA, SCHEME_ID, READING_DATE, READING_ID))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(BASELINE, BASELINE_AT)));
        when(repo.findLatestFlowReadingByOperator(SCHEMA, OPERATOR_ID))
                .thenReturn(Optional.of(target(quarantineReason)));
        when(glificOperatorContextService.resolveOperatorWithSchema(CONTACT, TENANT_ID))
                .thenReturn(new TelemetryOperatorWithSchema(SCHEMA, operator));
    }

    private CreateReadingResponse correct(SupplyPlausibilityProperties.Mode mode, BigDecimal value) {
        return service(mode).updateConfirmedReading(null, CONTACT, value, TENANT_ID);
    }

    private void verifyNoWrite() {
        verify(repo, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong());
        verify(repo, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any());
        verify(repo, never()).applyQuarantineReason(anyString(), anyLong(), anyInt());
        verify(telemetryEventPublisher, never()).publishMeterReadingRecorded(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private TenantAnomalyRecord capturedAnomaly() {
        ArgumentCaptor<TenantAnomalyRecord> anomaly = ArgumentCaptor.forClass(TenantAnomalyRecord.class);
        verify(repo).createTenantAnomalyRecord(eq(SCHEMA), anomaly.capture());
        assertThat(anomaly.getValue().userId()).isEqualTo(OPERATOR_ID);
        assertThat(anomaly.getValue().schemeId()).isEqualTo(SCHEME_ID);
        assertThat(anomaly.getValue().type()).isEqualTo(AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY);
        assertThat(anomaly.getValue().status()).isEqualTo(AnomalyConstants.STATUS_OPEN);
        return anomaly.getValue();
    }

    private String capturedAnomalyReason() {
        return capturedAnomaly().reason();
    }

    @Nested
    @DisplayName("a correction that passes the check")
    class Accepted {

        /**
         * The release path: the row's first ever publication. Nothing about it is special-cased —
         * it is the same write and the same unconditional flag clear as a clean target gets.
         */
        @Test
        @DisplayName("writes the value, clears the flag and publishes when the target was quarantined")
        void releasesQuarantinedTarget() {
            checkableScheme(QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY);

            CreateReadingResponse response = correct(SupplyPlausibilityProperties.Mode.ENFORCE, PLAUSIBLE);

            verify(repo).updateConfirmedReading(eq(SCHEMA), eq(READING_ID), eq(PLAUSIBLE), eq(OPERATOR_ID), any());
            verify(repo).applyQuarantineReason(SCHEMA, READING_ID, QuarantineReason.NONE);
            verify(telemetryEventPublisher).publishMeterReadingRecorded(
                    eq(TENANT_ID), eq(SCHEME_ID), eq(OPERATOR_ID), any(), eq(PLAUSIBLE), isNull(),
                    any(), any(), any(), eq(READING_DATE), eq(1), eq(0));
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getQualityStatus()).isEqualTo("CONFIRMED");
        }

        /** Regression guard on the path that existed before this feature. */
        @Test
        @DisplayName("writes the value and publishes when the target was already published")
        void correctsCleanTarget() {
            checkableScheme(QuarantineReason.NONE);

            CreateReadingResponse response = correct(SupplyPlausibilityProperties.Mode.ENFORCE, PLAUSIBLE);

            verify(repo).updateConfirmedReading(eq(SCHEMA), eq(READING_ID), eq(PLAUSIBLE), eq(OPERATOR_ID), any());
            verify(repo).applyQuarantineReason(SCHEMA, READING_ID, QuarantineReason.NONE);
            verify(telemetryEventPublisher).publishMeterReadingRecorded(
                    eq(TENANT_ID), eq(SCHEME_ID), eq(OPERATOR_ID), any(), eq(PLAUSIBLE), isNull(),
                    any(), any(), any(), eq(READING_DATE), eq(1), eq(0));
            assertThat(response.isSuccess()).isTrue();
        }

        /** The row being corrected must not be its own baseline. */
        @Test
        @DisplayName("excludes the corrected row from its own baseline")
        void excludesSelfFromBaseline() {
            checkableScheme(QuarantineReason.NONE);

            correct(SupplyPlausibilityProperties.Mode.ENFORCE, PLAUSIBLE);

            verify(repo).findLatestConfirmedReadingSnapshotBeforeDate(
                    SCHEMA, SCHEME_ID, READING_DATE, READING_ID);
        }
    }

    @Nested
    @DisplayName("a correction that fails the check")
    class Refused {

        /**
         * One expectation over both target states. The whole point of §6.3 is that these two cases
         * are indistinguishable in what they write; only the anomaly text differs.
         */
        @ParameterizedTest(name = "target quarantineReason={0}")
        @ValueSource(ints = {QuarantineReason.NONE, QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY})
        @DisplayName("writes nothing and publishes no reading, whatever the target's state")
        void neverWrites(int targetQuarantineReason) {
            checkableScheme(targetQuarantineReason);

            CreateReadingResponse response = correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            verifyNoWrite();
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getQualityStatus()).isEqualTo("REJECTED");
            assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
        }

        /**
         * Not merely "no call was made": the value the caller gets back as the standing reading is
         * the one that was already there, so the stored figure is asserted and not just the silence.
         */
        @ParameterizedTest(name = "target quarantineReason={0}")
        @ValueSource(ints = {QuarantineReason.NONE, QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY})
        @DisplayName("leaves confirmed_reading exactly as it was")
        void leavesStoredValueIntact(int targetQuarantineReason) {
            checkableScheme(targetQuarantineReason);

            CreateReadingResponse response = correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            assertThat(response.getLastConfirmedReading()).isEqualByComparingTo(STANDING);
            assertThat(response.getMeterReading()).isEqualByComparingTo(IMPLAUSIBLE);
        }

        /** THRESHOLD-DISCLOSURE: the refusal names no ceiling, population or connection count. */
        @Test
        @DisplayName("discloses no ceiling, population or FHTC figure")
        void disclosesNoThreshold() {
            checkableScheme(QuarantineReason.NONE);

            CreateReadingResponse response = correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            assertThat(response.getMessage()).doesNotContain("75000", "75,000", "500", "150", "100");
        }

        @Test
        @DisplayName("reason B — the target is published, so the published value is said to stand")
        void reasonForPublishedTarget() {
            checkableScheme(QuarantineReason.NONE);

            correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            assertThat(capturedAnomalyReason())
                    .isEqualTo(AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_PUBLISHED);
        }

        @Test
        @DisplayName("reason C — the target is quarantined, so the day is said to stay missing")
        void reasonForQuarantinedTarget() {
            checkableScheme(QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY);

            correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            assertThat(capturedAnomalyReason())
                    .isEqualTo(AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_QUARANTINED);
        }

        /**
         * equals(), not contains(): a contains assertion would still pass if someone appended the
         * litres or the ceiling, which is exactly what would make anomaly_table.reason ungroupable.
         */
        @ParameterizedTest(name = "target quarantineReason={0}")
        @ValueSource(ints = {QuarantineReason.NONE, QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY})
        @DisplayName("reason carries no interpolated per-row values")
        void reasonHasNoDigits(int targetQuarantineReason) {
            checkableScheme(targetQuarantineReason);

            correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            assertThat(capturedAnomalyReason()).doesNotContainPattern("\\d");
        }

        /**
         * previousReading is the baseline the litres were measured against, not the standing stored
         * value, so (overriddenReading - previousReading) * 1000 reproduces the decision from the
         * persisted anomaly row alone.
         */
        @Test
        @DisplayName("publishes the attempted value against the baseline it was judged on")
        void publishesAttemptAgainstBaseline() {
            checkableScheme(QuarantineReason.NONE);

            correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            verify(telemetryEventPublisher).publishAnomalyRecorded(
                    eq(TENANT_ID),
                    eq(AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY),
                    eq(OPERATOR_ID),
                    eq(SCHEME_ID),
                    isNull(),
                    isNull(),
                    eq(IMPLAUSIBLE),
                    eq(0),
                    eq(BASELINE),
                    eq(BASELINE_AT),
                    eq(0),
                    eq(AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_PUBLISHED),
                    eq(AnomalyConstants.STATUS_OPEN),
                    isNull());
        }

        /**
         * The same numbers have to reach the tenant schema, not only the warehouse. They did not
         * once: the tenant insert wrote the identifying columns alone, so a refused correction landed
         * there with previous_reading and overridden_reading NULL while analytics held both.
         */
        @Test
        @DisplayName("the tenant row carries the attempt and the baseline too, not just the event")
        void tenantRowCarriesTheSameNumbersAsTheEvent() {
            checkableScheme(QuarantineReason.NONE);

            correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            TenantAnomalyRecord anomaly = capturedAnomaly();
            assertThat(anomaly.overriddenReading()).isEqualByComparingTo(IMPLAUSIBLE);
            assertThat(anomaly.previousReading()).isEqualByComparingTo(BASELINE);
            assertThat(anomaly.previousReadingDate()).isEqualTo(BASELINE_AT);
            // (1100 - 900) * 1000 = 200,000 L, recomputable from the persisted row alone.
            assertThat(anomaly.overriddenReading().subtract(anomaly.previousReading())
                    .multiply(BigDecimal.valueOf(1000))).isEqualByComparingTo("200000");
            assertThat(anomaly.reason())
                    .isEqualTo(AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_PUBLISHED);
        }

        /**
         * Analytics dedups on a uuid derived from the correlationId, so a deterministic key would
         * collapse the second attempt into the first. A null correlationId keeps every attempt.
         */
        @Test
        @DisplayName("records every rejected attempt, not one touched row")
        void rejectedAttemptsAccumulate() {
            checkableScheme(QuarantineReason.NONE);
            BfmReadingService service = service(SupplyPlausibilityProperties.Mode.ENFORCE);

            service.updateConfirmedReading(null, CONTACT, IMPLAUSIBLE, TENANT_ID);
            service.updateConfirmedReading(null, CONTACT, new BigDecimal("1200"), TENANT_ID);

            verify(repo, times(2)).createTenantAnomalyRecord(eq(SCHEMA), argThat(anomaly ->
                    anomaly.type() == AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY
                            && anomaly.reason() != null));
            verify(telemetryEventPublisher, times(2)).publishAnomalyRecorded(
                    any(), eq(AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), anyString(), any(), isNull());
        }
    }

    @Nested
    @DisplayName("modes other than ENFORCE")
    class Modes {

        /** AUDIT logs and counts; the correction goes through exactly as it did before. */
        @Test
        @DisplayName("AUDIT applies the correction and records no anomaly")
        void auditDoesNotRefuse() {
            checkableScheme(QuarantineReason.NONE);

            CreateReadingResponse response = correct(SupplyPlausibilityProperties.Mode.AUDIT, IMPLAUSIBLE);

            verify(repo).updateConfirmedReading(eq(SCHEMA), eq(READING_ID), eq(IMPLAUSIBLE), eq(OPERATOR_ID), any());
            verify(repo, never()).createTenantAnomalyRecord(any(), any());
            assertThat(response.isSuccess()).isTrue();
        }

        /**
         * OFF is a true kill switch: the scheme is never even looked up. The flag is still cleared,
         * so a row quarantined during an earlier ENFORCE window is not stranded outside every
         * baseline with no way to release it.
         */
        @Test
        @DisplayName("OFF skips the check entirely but still releases the row")
        void offSkipsTheCheck() {
            when(repo.findLatestFlowReadingByOperator(SCHEMA, OPERATOR_ID))
                    .thenReturn(Optional.of(target(QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY)));
            when(glificOperatorContextService.resolveOperatorWithSchema(CONTACT, TENANT_ID))
                    .thenReturn(new TelemetryOperatorWithSchema(SCHEMA, operator));

            CreateReadingResponse response = correct(SupplyPlausibilityProperties.Mode.OFF, IMPLAUSIBLE);

            verify(repo, never()).findSchemeSupplyCounts(any(), any());
            verify(repo, never()).findLatestConfirmedReadingSnapshotBeforeDate(any(), any(), any(), any());
            verify(repo).applyQuarantineReason(SCHEMA, READING_ID, QuarantineReason.NONE);
            assertThat(response.isSuccess()).isTrue();
        }

        /**
         * A pre-V40 tenant has no column to mark, so the check cannot run — and clearing a flag it
         * has no column for is a guarded no-op in the repository, so the call is still made.
         */
        @Test
        @DisplayName("an unmigrated tenant schema skips the check")
        void unmigratedSchemaSkipsTheCheck() {
            when(repo.supportsQuarantine(SCHEMA)).thenReturn(false);
            when(repo.findLatestFlowReadingByOperator(SCHEMA, OPERATOR_ID))
                    .thenReturn(Optional.of(target(QuarantineReason.NONE)));
            when(glificOperatorContextService.resolveOperatorWithSchema(CONTACT, TENANT_ID))
                    .thenReturn(new TelemetryOperatorWithSchema(SCHEMA, operator));

            CreateReadingResponse response = correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            verify(repo, never()).findLatestConfirmedReadingSnapshotBeforeDate(any(), any(), any(), any());
            assertThat(response.isSuccess()).isTrue();
        }

        /**
         * The delta is only a water volume on a cumulative m&sup3; index. An electricity or
         * pump-duration reading is a different quantity, so the ceiling means nothing for it.
         */
        @Test
        @DisplayName("a non-BFM row is not checked")
        void nonBfmRowSkipsTheCheck() {
            // Migrated on purpose, and lenient because the channel gate short-circuits before the
            // schema is ever consulted: the only reason this row goes unchecked must be its channel.
            lenient().when(repo.supportsQuarantine(SCHEMA)).thenReturn(true);
            when(repo.findLatestFlowReadingByOperator(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(
                    new TelemetryLatestFlowReadingRecord(
                            READING_ID, SCHEME_ID, OPERATOR_ID, CORRELATION_ID,
                            new BigDecimal("948"), STANDING, "https://img/1.jpg",
                            READING_DATE, READING_AT, ReadingChannel.ELM.name(), QuarantineReason.NONE)));
            when(glificOperatorContextService.resolveOperatorWithSchema(CONTACT, TENANT_ID))
                    .thenReturn(new TelemetryOperatorWithSchema(SCHEMA, operator));

            CreateReadingResponse response = correct(SupplyPlausibilityProperties.Mode.ENFORCE, IMPLAUSIBLE);

            verify(repo, never()).findLatestConfirmedReadingSnapshotBeforeDate(any(), any(), any(), any());
            assertThat(response.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("the correlationId route")
    class ByCorrelationId {

        /** Both resolution routes converge on the same record, so both get the same rule. */
        @Test
        @DisplayName("refuses an implausible correction resolved by correlationId")
        void refusesByCorrelationId() {
            when(repo.findSchemaNameByTenantId(TENANT_ID)).thenReturn(Optional.of(SCHEMA));
            when(repo.supportsQuarantine(SCHEMA)).thenReturn(true);
            when(repo.findFlowReadingDetailsByCorrelationId(SCHEMA, CORRELATION_ID))
                    .thenReturn(Optional.of(target(QuarantineReason.NONE)));
            when(repo.findSchemeSupplyCounts(SCHEMA, SCHEME_ID))
                    .thenReturn(Optional.of(new TelemetrySchemeSupplyCounts(100, 0, 0)));
            when(repo.findLatestConfirmedReadingSnapshotBeforeDate(
                    SCHEMA, SCHEME_ID, READING_DATE, READING_ID))
                    .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(BASELINE, BASELINE_AT)));

            CreateReadingResponse response = service(SupplyPlausibilityProperties.Mode.ENFORCE)
                    .updateConfirmedReading(CORRELATION_ID, null, IMPLAUSIBLE, TENANT_ID);

            verifyNoWrite();
            assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
            assertThat(capturedAnomalyReason())
                    .isEqualTo(AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_PUBLISHED);
        }
    }
}
