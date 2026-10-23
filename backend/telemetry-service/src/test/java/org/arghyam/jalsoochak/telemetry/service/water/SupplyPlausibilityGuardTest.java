package org.arghyam.jalsoochak.telemetry.service.water;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSupplyCounts;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SupplyPlausibilityGuard")
class SupplyPlausibilityGuardTest {

    private static final String SCHEMA = "tenant_as";
    private static final int TENANT_ID = 1;
    private static final long OPERATOR_ID = 7L;
    private static final long SCHEME_ID = 10L;
    private static final LocalDate READING_DATE = LocalDate.of(2026, 9, 8);

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private TenantConfigRepository tenantConfigRepository;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private SupplyPlausibilityGuard guard(SupplyPlausibilityProperties.Mode mode) {
        return new SupplyPlausibilityGuard(
                telemetryTenantRepository, tenantConfigRepository, new ObjectMapper(),
                SupplyPlausibilityFixtures.properties(mode), meterRegistry);
    }

    /** 100 connections x 5 persons = 500 people; ceiling 500 x 150 = 75,000 L/day. */
    private void schemeWith100Connections() {
        when(telemetryTenantRepository.findSchemeSupplyCounts(SCHEMA, SCHEME_ID))
                .thenReturn(Optional.of(new TelemetrySchemeSupplyCounts(100, 0, 0)));
    }

    /** A submission measured against a baseline read the day before. */
    private Verdict assess(SupplyPlausibilityGuard guard, String confirmed, String baseline) {
        return assess(guard, confirmed, baseline, READING_DATE.minusDays(1), SupplyPlausibilityGuard.Path.SUBMISSION);
    }

    /** @param baselineDate the day the baseline was read on; {@code null} for a baseline with no reading time */
    private Verdict assess(SupplyPlausibilityGuard guard, String confirmed, String baseline,
                           LocalDate baselineDate, SupplyPlausibilityGuard.Path path) {
        TelemetryConfirmedReadingSnapshot snapshot = baseline == null
                ? null
                : new TelemetryConfirmedReadingSnapshot(new BigDecimal(baseline), null,
                        baselineDate == null ? null : baselineDate.atTime(9, 0));
        return guard.assess(SCHEMA, TENANT_ID, OPERATOR_ID, SCHEME_ID, READING_DATE,
                new BigDecimal(confirmed), snapshot, path);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    @Nested
    @DisplayName("verdicts")
    class Verdicts {

        @Test
        @DisplayName("a plausible day is accepted and counted")
        void acceptsAndCounts() {
            schemeWith100Connections();

            assertThat(assess(guard(SupplyPlausibilityProperties.Mode.ENFORCE), "950", "900"))
                    .isInstanceOf(Verdict.Accepted.class);
            assertThat(counter("implausible_supply.accepted", "path", "submission")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("an implausible day under ENFORCE counts as quarantined")
        void enforceCountsQuarantined() {
            schemeWith100Connections();

            assertThat(assess(guard(SupplyPlausibilityProperties.Mode.ENFORCE), "1100", "900"))
                    .isInstanceOf(Verdict.Quarantined.class);
            assertThat(counter("implausible_supply.quarantined", "path", "submission")).isEqualTo(1.0);
            assertThat(counter("implausible_supply.would_quarantine")).isZero();
        }

        @Test
        @DisplayName("the same day under AUDIT returns the same verdict but counts would_quarantine")
        void auditCountsWouldQuarantine() {
            schemeWith100Connections();

            // The verdict is identical: AUDIT and ENFORCE run the same evaluation, and only the
            // caller's response to it differs. If AUDIT short-circuited the evaluation instead, the
            // audit period would have nothing to measure.
            assertThat(assess(guard(SupplyPlausibilityProperties.Mode.AUDIT), "1100", "900"))
                    .isInstanceOf(Verdict.Quarantined.class);
            assertThat(counter("implausible_supply.would_quarantine", "path", "submission")).isEqualTo(1.0);
            assertThat(counter("implausible_supply.quarantined")).isZero();
        }

        @Test
        @DisplayName("a scheme with no recorded connections is skipped and counted by reason")
        void skipsWithoutPopulation() {
            when(telemetryTenantRepository.findSchemeSupplyCounts(SCHEMA, SCHEME_ID))
                    .thenReturn(Optional.of(new TelemetrySchemeSupplyCounts(0, 0, 0)));

            assertThat(assess(guard(SupplyPlausibilityProperties.Mode.ENFORCE), "1100", "900"))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NO_POPULATION));
            assertThat(counter("implausible_supply.skipped", "reason", "no_population")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a scheme missing from master data is treated as having no connections")
        void missingSchemeRowIsNoPopulation() {
            when(telemetryTenantRepository.findSchemeSupplyCounts(SCHEMA, SCHEME_ID))
                    .thenReturn(Optional.empty());

            assertThat(assess(guard(SupplyPlausibilityProperties.Mode.ENFORCE), "1100", "900"))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NO_POPULATION));
        }

        @Test
        @DisplayName("a scheme with no earlier reading is skipped before any master data is read")
        void skipsWithoutBaseline() {
            // No stub for findSchemeSupplyCounts: strict stubs would fail if it were consulted.
            assertThat(assess(guard(SupplyPlausibilityProperties.Mode.ENFORCE), "950", null))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NO_BASELINE));
        }
    }

    @Nested
    @DisplayName("days since the baseline")
    class DaysSinceBaseline {

        @Test
        @DisplayName("allows the ceiling for every day since the baseline's reading")
        void scalesByTheBaselinesDay() {
            schemeWith100Connections();

            // 200 m3 since a reading three days earlier: 225,000 L allowed, not 75,000.
            Verdict verdict = assess(guard(SupplyPlausibilityProperties.Mode.ENFORCE), "1100", "900",
                    READING_DATE.minusDays(3), SupplyPlausibilityGuard.Path.SUBMISSION);

            assertThat(verdict).isInstanceOf(Verdict.Accepted.class);
            assertThat(((Verdict.Accepted) verdict).ceiling()).isEqualByComparingTo("225000");
        }

        @Test
        @DisplayName("counts a baseline with no reading time as one day")
        void unknownReadingTimeIsOneDay() {
            schemeWith100Connections();

            Verdict verdict = assess(guard(SupplyPlausibilityProperties.Mode.ENFORCE), "1100", "900",
                    null, SupplyPlausibilityGuard.Path.SUBMISSION);

            assertThat(verdict).isInstanceOf(Verdict.Quarantined.class);
            assertThat(((Verdict.Quarantined) verdict).ceiling()).isEqualByComparingTo("75000");
        }
    }

    @Nested
    @DisplayName("release path")
    class ReleasePath {

        @Test
        @DisplayName("counts an implausible reading as quarantined whatever the mode")
        void actsOnTheVerdictUnderAudit() {
            schemeWith100Connections();

            Verdict verdict = assess(guard(SupplyPlausibilityProperties.Mode.AUDIT), "1100", "900",
                    READING_DATE.minusDays(1), SupplyPlausibilityGuard.Path.RELEASE);

            assertThat(verdict).isInstanceOf(Verdict.Quarantined.class);
            assertThat(counter("implausible_supply.quarantined", "path", "release", "mode", "AUDIT"))
                    .isEqualTo(1.0);
            assertThat(counter("implausible_supply.would_quarantine")).isZero();
        }
    }

    @Nested
    @DisplayName("household size")
    class HouseholdSize {

        @Test
        @DisplayName("prefers the tenant's own AVERAGE_MEMBERS_PER_HOUSEHOLD")
        void prefersTenantConfig() {
            when(tenantConfigRepository.findConfigValue(TENANT_ID, "AVERAGE_MEMBERS_PER_HOUSEHOLD"))
                    .thenReturn(Optional.of("{\"value\":\"4.5\"}"));

            assertThat(guard(SupplyPlausibilityProperties.Mode.AUDIT).resolveMembersPerHousehold(TENANT_ID))
                    .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("4.5"));
        }

        @Test
        @DisplayName("falls back to the configured default when the tenant has no config")
        void fallsBackToDefault() {
            when(tenantConfigRepository.findConfigValue(TENANT_ID, "AVERAGE_MEMBERS_PER_HOUSEHOLD"))
                    .thenReturn(Optional.empty());

            assertThat(guard(SupplyPlausibilityProperties.Mode.AUDIT).resolveMembersPerHousehold(TENANT_ID))
                    .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("5"));
        }

        @Test
        @DisplayName("falls back rather than throwing on a malformed config")
        void fallsBackOnMalformedConfig() {
            when(tenantConfigRepository.findConfigValue(TENANT_ID, "AVERAGE_MEMBERS_PER_HOUSEHOLD"))
                    .thenReturn(Optional.of("{\"value\":\"four and a half\"}"));

            assertThat(guard(SupplyPlausibilityProperties.Mode.AUDIT).resolveMembersPerHousehold(TENANT_ID))
                    .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("5"));
        }

        @Test
        @DisplayName("with no default configured, an unusable config skips the check")
        void noDefaultMeansSkip() {
            when(tenantConfigRepository.findConfigValue(TENANT_ID, "AVERAGE_MEMBERS_PER_HOUSEHOLD"))
                    .thenReturn(Optional.of("{\"value\":\"0\"}"));
            lenient().when(telemetryTenantRepository.findSchemeSupplyCounts(SCHEMA, SCHEME_ID))
                    .thenReturn(Optional.of(new TelemetrySchemeSupplyCounts(100, 0, 0)));

            SupplyPlausibilityGuard guard = new SupplyPlausibilityGuard(
                    telemetryTenantRepository, tenantConfigRepository, new ObjectMapper(),
                    SupplyPlausibilityFixtures.properties(
                            SupplyPlausibilityProperties.Mode.ENFORCE, "150", ""),
                    meterRegistry);

            assertThat(assess(guard, "1100", "900"))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NO_MEMBERS_PER_HOUSEHOLD));
        }

        @Test
        @DisplayName("the tenant config changes the ceiling, not just the reported number")
        void tenantConfigMovesTheCeiling() {
            schemeWith100Connections();
            when(tenantConfigRepository.findConfigValue(TENANT_ID, "AVERAGE_MEMBERS_PER_HOUSEHOLD"))
                    .thenReturn(Optional.of("{\"value\":\"2\"}"));

            // 100 x 2 = 200 people, ceiling 30,000 L. The 50,000 L day that passes at 5 persons fails.
            assertThat(assess(guard(SupplyPlausibilityProperties.Mode.ENFORCE), "950", "900"))
                    .isInstanceOf(Verdict.Quarantined.class);
        }
    }

    @Nested
    @DisplayName("mode")
    class Mode {

        @Test
        @DisplayName("reports OFF as disabled so callers can skip gathering inputs")
        void offIsDisabled() {
            SupplyPlausibilityGuard guard = guard(SupplyPlausibilityProperties.Mode.OFF);

            assertThat(guard.isDisabled()).isTrue();
            assertThat(guard.isEnforcing()).isFalse();
        }

        @Test
        @DisplayName("reports AUDIT as neither disabled nor enforcing")
        void auditIsNeither() {
            SupplyPlausibilityGuard guard = guard(SupplyPlausibilityProperties.Mode.AUDIT);

            assertThat(guard.isDisabled()).isFalse();
            assertThat(guard.isEnforcing()).isFalse();
        }
    }

    @Test
    @DisplayName("works without a MeterRegistry, so tests and cut-down contexts need not stand one up")
    void toleratesNullRegistry() {
        schemeWith100Connections();
        SupplyPlausibilityGuard guard = new SupplyPlausibilityGuard(
                telemetryTenantRepository, tenantConfigRepository, new ObjectMapper(),
                SupplyPlausibilityFixtures.properties(SupplyPlausibilityProperties.Mode.ENFORCE), null);

        assertThat(assess(guard, "1100", "900")).isInstanceOf(Verdict.Quarantined.class);
    }
}
