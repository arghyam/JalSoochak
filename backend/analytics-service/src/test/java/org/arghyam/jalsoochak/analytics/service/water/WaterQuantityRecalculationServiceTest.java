package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.entity.FactWaterQuantity;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.repository.FactWaterQuantityRepository;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.Derived;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaterQuantityRecalculationServiceTest {

    private static final int TENANT = 1;
    private static final int SCHEME = 11;
    private static final int OPERATOR_A = 21;
    private static final int OPERATOR_B = 22;
    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);
    private static final CalculationParameters PUMP_SNAPSHOT = new CalculationParameters(1, null, null, List.of());

    @Mock
    private FactMeterReadingRepository meterReadingRepository;
    @Mock
    private FactWaterQuantityRepository waterQuantityRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private WaterQuantityRecalculationService service;

    /**
     * Stands in for the PDU calculator phase 5 adds: 100 L per minute, and not derivable without a
     * snapshot — enough to exercise the PERIOD_AMOUNT day total without the real formulas.
     */
    private static final class StubPduCalculator implements WaterQuantityCalculator {
        @Override
        public ReadingChannel channel() {
            return ReadingChannel.PDU;
        }

        @Override
        public WaterQuantityOutcome calculate(WaterQuantityContext context) {
            if (context.parameters() == null) {
                return WaterQuantityOutcome.notDerivable(Reason.NO_ACTIVE_PUMP);
            }
            return WaterQuantityOutcome.derived(context.amount().multiply(BigDecimal.valueOf(100)).longValueExact());
        }
    }

    /** Stands in for a calculator whose litres are already at the edge of {@code long}. */
    private static final class HugePduCalculator implements WaterQuantityCalculator {
        @Override
        public ReadingChannel channel() {
            return ReadingChannel.PDU;
        }

        @Override
        public WaterQuantityOutcome calculate(WaterQuantityContext context) {
            return WaterQuantityOutcome.derived(Long.MAX_VALUE - 10);
        }
    }

    /** Stands in for the ELM calculator phase 5 adds: 1,000 L per kWh. */
    private static final class StubElmCalculator implements WaterQuantityCalculator {
        @Override
        public ReadingChannel channel() {
            return ReadingChannel.ELM;
        }

        @Override
        public WaterQuantityOutcome calculate(WaterQuantityContext context) {
            return WaterQuantityOutcome.derived(context.amount().multiply(BigDecimal.valueOf(1000)).longValueExact());
        }
    }

    @BeforeEach
    void setUp() {
        service = serviceWith(new StubPduCalculator());
    }

    /** A service with the BFM calculator and {@code others}. */
    private WaterQuantityRecalculationService serviceWith(WaterQuantityCalculator... others) {
        List<WaterQuantityCalculator> calculators = new ArrayList<>(List.of(others));
        calculators.add(new BfmWaterQuantityCalculator());
        return new WaterQuantityRecalculationService(
                meterReadingRepository,
                waterQuantityRepository,
                new WaterQuantityCalculatorRegistry(calculators),
                new WaterQuantityRangeReporter(meterRegistry, 100_000L),
                meterRegistry);
    }

    // ---- METER_INDEX (BFM) ------------------------------------------------------------------

    @Test
    void meterIndex_storesTheIncreaseOverTheLatestEarlierReadingInLitres() {
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "100");
        noDayRow(D2);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).singleElement()
                .extracting(FactWaterQuantity::getWaterQuantity).isEqualTo(50_000L);
    }

    @Test
    void meterIndex_whenTheMeterWentBackwards_storesZero() {
        latestOn(D2, reading(ReadingChannel.BFM, "95", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "100");
        noDayRow(D2);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).singleElement()
                .extracting(FactWaterQuantity::getWaterQuantity).isEqualTo(0L);
    }

    @Test
    void meterIndex_whenNoEarlierReadingExists_storesZeroNotTheWholeMeterIndex() {
        latestOn(D2, reading(ReadingChannel.BFM, "1250000", OPERATOR_A, D2));
        noDayRow(D2);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).singleElement()
                .extracting(FactWaterQuantity::getWaterQuantity).isEqualTo(0L);
    }

    @Test
    void meterIndex_measuresFromTheDaysOwnChannelOnly() {
        // The starting point is asked for on the day's channel: an ELM kWh total is never a BFM
        // reading's starting point.
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        noDayRow(D2);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(meterReadingRepository).findLatestBefore(TENANT, SCHEME, D2, ReadingChannel.BFM);
    }

    @Test
    void meterIndex_aStartingPointWithNoOtherChannelReadingInBetweenIsUsed() {
        latestOn(D3, reading(ReadingChannel.BFM, "180", OPERATOR_A, D3));
        startingPoint(D3, ReadingChannel.BFM, "100", D1);
        anotherChannelReadBetween(D1, D3, ReadingChannel.BFM, false);
        noDayRow(D3);

        service.recalculateAfterReading(TENANT, SCHEME, D3);

        assertThat(savedRows()).singleElement()
                .extracting(FactWaterQuantity::getWaterQuantity).isEqualTo(80_000L);
    }

    @Test
    void meterIndex_aStartingPointAcrossAnotherChannelsReadingIsNotUsed() {
        // D2 was counted on another channel. Measuring D3 from D1 would count D2's water again, so D3
        // has no starting point, like a scheme's first reading.
        latestOn(D3, reading(ReadingChannel.BFM, "180", OPERATOR_A, D3));
        startingPoint(D3, ReadingChannel.BFM, "100", D1);
        anotherChannelReadBetween(D1, D3, ReadingChannel.BFM, true);
        noDayRow(D3);

        service.recalculateAfterReading(TENANT, SCHEME, D3);

        assertThat(savedRows()).singleElement()
                .extracting(FactWaterQuantity::getWaterQuantity).isEqualTo(0L);
    }

    @Test
    void meterIndex_legacyNullChannelIsBfm() {
        latestOn(D2, reading(null, "150", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "100");
        noDayRow(D2);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).singleElement()
                .extracting(FactWaterQuantity::getWaterQuantity).isEqualTo(50_000L);
    }

    @Test
    void meterIndex_unconfirmedLatestReadingLeavesTheDayAlone() {
        latestOn(D2, reading(ReadingChannel.BFM, null, OPERATOR_A, D2));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(waterQuantityRepository, never()).save(any());
        verify(waterQuantityRepository, never()).deleteReadingDerivedDay(any(), any(), any());
    }

    @Test
    void aDayWithNoStoredReadingIsLeftAlone() {
        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(waterQuantityRepository, never()).save(any());
    }

    @Test
    void aTotalTooLargeToStoreLeavesTheRowAloneAndIsCounted() {
        latestOn(D2, reading(ReadingChannel.BFM, "1e16", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "100");

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(waterQuantityRepository, never()).save(any());
        verify(waterQuantityRepository, never()).deleteReadingDerivedDay(any(), any(), any());
        assertThat(meterRegistry.counter("water_quantity.unstorable", "source", "reading").count()).isEqualTo(1.0);
    }

    @Test
    void anImplausibleTotalIsStoredAndCounted() {
        latestOn(D2, reading(ReadingChannel.BFM, "5000000", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "1");
        noDayRow(D2);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).singleElement()
                .extracting(FactWaterQuantity::getWaterQuantity).isEqualTo(4_999_999_000L);
        assertThat(meterRegistry.counter("water_quantity.implausible", "source", "reading").count()).isEqualTo(1.0);
    }

    @Test
    void anExistingRowIsOverwrittenAndItsReasonsCleared() {
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "100");
        FactWaterQuantity existing = dayRow(D2, 0L, OPERATOR_B, 0);
        existing.setOutageReason("power cut");
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(TENANT, SCHEME, D2))
                .thenReturn(Optional.of(existing));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        FactWaterQuantity saved = savedRows().get(0);
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getWaterQuantity()).isEqualTo(50_000L);
        assertThat(saved.getOutageReason()).isNull();
        assertThat(saved.getNonSubmissionReason()).isNull();
    }

    // ---- who the day is credited to ---------------------------------------------------------

    @Test
    void theDayIsCreditedToItsLatestReadingNotToTheReadingThatTriggeredIt() {
        // Operator A corrects D2; D3's latest reading is operator B's. The follow-up recalculates D3
        // because its amount starts from D2 — it must stay B's day.
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "100");
        noDayRow(D2);
        nextReadingDate(D2, ReadingChannel.BFM, D3);
        FactMeterReading bOnD3 = reading(ReadingChannel.BFM, "180", OPERATOR_B, D3);
        bOnD3.setSubmissionStatus(0);
        latestOn(D3, bOnD3);
        startingPoint(D3, ReadingChannel.BFM, "150");
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(TENANT, SCHEME, D3))
                .thenReturn(Optional.of(dayRow(D3, 20_000L, OPERATOR_B, 0)));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        List<FactWaterQuantity> saved = savedRows();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getUserId()).isEqualTo(OPERATOR_A);
        assertThat(saved.get(1).getDate()).isEqualTo(D3);
        assertThat(saved.get(1).getWaterQuantity()).isEqualTo(30_000L);
        assertThat(saved.get(1).getUserId()).isEqualTo(OPERATOR_B);
        assertThat(saved.get(1).getSubmissionStatus()).isZero();
    }

    @Test
    void aLegacyReadingWithNoStatusCreditsTheDayAsSubmitted() {
        FactMeterReading legacy = reading(ReadingChannel.BFM, "150", OPERATOR_A, D2);
        legacy.setSubmissionStatus(null);
        latestOn(D2, legacy);
        noDayRow(D2);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows().get(0).getSubmissionStatus()).isEqualTo(1);
    }

    // ---- the follow-up ----------------------------------------------------------------------

    @Test
    void theFollowUpDoesNotWriteOrMoveUpdatedAtWhenNothingChanged() {
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "100");
        noDayRow(D2);
        nextReadingDate(D2, ReadingChannel.BFM, D3);
        latestOn(D3, reading(ReadingChannel.BFM, "180", OPERATOR_B, D3));
        startingPoint(D3, ReadingChannel.BFM, "150");
        FactWaterQuantity untouched = dayRow(D3, 30_000L, OPERATOR_B, 1);
        LocalDateTime lastSubmission = untouched.getUpdatedAt();
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(TENANT, SCHEME, D3))
                .thenReturn(Optional.of(untouched));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).singleElement().extracting(FactWaterQuantity::getDate).isEqualTo(D2);
        assertThat(untouched.getUpdatedAt()).isEqualTo(lastSubmission);
    }

    @Test
    void theFollowUpWritesWhenOnlyAReasonNeedsClearing() {
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "100");
        noDayRow(D2);
        nextReadingDate(D2, ReadingChannel.BFM, D3);
        latestOn(D3, reading(ReadingChannel.BFM, "180", OPERATOR_B, D3));
        startingPoint(D3, ReadingChannel.BFM, "150");
        FactWaterQuantity withReason = dayRow(D3, 30_000L, OPERATOR_B, 1);
        withReason.setNonSubmissionReason("meter changed");
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(TENANT, SCHEME, D3))
                .thenReturn(Optional.of(withReason));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).hasSize(2);
        assertThat(withReason.getNonSubmissionReason()).isNull();
    }

    @Test
    void theFollowUpRunsAfterABfmReadingEvenWhenTheDaysLatestReadingIsElm() {
        // D2's latest reading is ELM, which has no calculator here, so D2 itself is left alone. The
        // BFM reading just written still starts D3's BFM amount, so D3 is recalculated regardless.
        latestOn(D2, reading(ReadingChannel.ELM, "40", OPERATOR_A, D2));
        nextReadingDate(D2, ReadingChannel.BFM, D3);
        latestOn(D3, reading(ReadingChannel.BFM, "180", OPERATOR_B, D3));
        startingPoint(D3, ReadingChannel.BFM, "150");
        noDayRow(D3);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).singleElement().satisfies(row -> {
            assertThat(row.getDate()).isEqualTo(D3);
            assertThat(row.getWaterQuantity()).isEqualTo(30_000L);
        });
    }

    @Test
    void theFollowUpRecalculatesTheNextDayOnAnotherMeterIndexChannel() {
        // D3's ELM amount was measured from D1's ELM reading. The BFM reading now written for D2 sits
        // between them, so D3 loses its starting point and its old total.
        WaterQuantityRecalculationService elmService = serviceWith(new StubPduCalculator(), new StubElmCalculator());
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        noStartingPoint(D2, ReadingChannel.BFM);
        noDayRow(D2);
        noNextReadingDate(D2, ReadingChannel.BFM);
        nextReadingDate(D2, ReadingChannel.ELM, D3);
        latestOn(D3, reading(ReadingChannel.ELM, "45", OPERATOR_B, D3));
        startingPoint(D3, ReadingChannel.ELM, "40", D1);
        anotherChannelReadBetween(D1, D3, ReadingChannel.ELM, true);
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(TENANT, SCHEME, D3))
                .thenReturn(Optional.of(dayRow(D3, 5_000L, OPERATOR_B, 1)));

        elmService.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).hasSize(2).last().satisfies(row -> {
            assertThat(row.getDate()).isEqualTo(D3);
            assertThat(row.getWaterQuantity()).isZero();
        });
    }

    @Test
    void aPeriodAmountReadingIsFollowedUpOnTheMeterIndexChannels() {
        // A PDU run on D2 now sits between D3's BFM reading and its D1 starting point.
        latestOn(D2, reading(ReadingChannel.PDU, "30", OPERATOR_A, D2, PUMP_SNAPSHOT));
        dayReadings(D2, reading(ReadingChannel.PDU, "30", OPERATOR_A, D2, PUMP_SNAPSHOT));
        noDayRow(D2);
        nextReadingDate(D2, ReadingChannel.BFM, D3);
        latestOn(D3, reading(ReadingChannel.BFM, "180", OPERATOR_B, D3));
        startingPoint(D3, ReadingChannel.BFM, "100", D1);
        anotherChannelReadBetween(D1, D3, ReadingChannel.BFM, true);
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(TENANT, SCHEME, D3))
                .thenReturn(Optional.of(dayRow(D3, 80_000L, OPERATOR_B, 1)));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).hasSize(2).last().satisfies(row -> {
            assertThat(row.getDate()).isEqualTo(D3);
            assertThat(row.getWaterQuantity()).isZero();
        });
    }

    @Test
    void theFollowUpGoesOneDatePerMeterIndexChannel() {
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        noDayRow(D2);
        nextReadingDate(D2, ReadingChannel.BFM, D3);
        latestOn(D3, reading(ReadingChannel.BFM, "180", OPERATOR_B, D3));
        noDayRow(D3);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(meterReadingRepository).findNextReadingDate(TENANT, SCHEME, D2, ReadingChannel.BFM.getCode());
        verify(meterReadingRepository).findNextReadingDate(TENANT, SCHEME, D2, ReadingChannel.ELM.getCode());
        verify(meterReadingRepository, times(2)).findNextReadingDate(any(), any(), any(), anyInt());
    }

    @Test
    void aDateNextOnTwoChannelsIsRecalculatedOnce() {
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        noDayRow(D2);
        nextReadingDate(D2, ReadingChannel.BFM, D3);
        nextReadingDate(D2, ReadingChannel.ELM, D3);
        latestOn(D3, reading(ReadingChannel.BFM, "180", OPERATOR_B, D3));
        noDayRow(D3);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(meterReadingRepository, times(1))
                .findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(TENANT, SCHEME, D3);
    }

    // ---- a channel with no calculator (Q14) -------------------------------------------------

    @Test
    void aDayWhoseChannelHasNoCalculatorKeepsItsRowAndIsCounted() {
        latestOn(D2, reading(ReadingChannel.ELM, "40", OPERATOR_A, D2));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(waterQuantityRepository, never()).findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(
                any(), any(), any());
        verify(waterQuantityRepository, never()).save(any());
        verify(waterQuantityRepository, never()).deleteReadingDerivedDay(any(), any(), any());
        assertThat(meterRegistry.counter("water_quantity.calculator.missing", "channel", "2").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("water_quantity.not_derivable").counter()).isNull();
    }

    @Test
    void aChannelWithNoKindIsTreatedAsHavingNoCalculator() {
        latestOn(D2, reading(ReadingChannel.IOT, "40", OPERATOR_A, D2));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(waterQuantityRepository, never()).save(any());
        assertThat(meterRegistry.counter("water_quantity.calculator.missing", "channel", "4").count()).isEqualTo(1.0);
    }

    // ---- PERIOD_AMOUNT (PDU) ----------------------------------------------------------------

    @Test
    void aPeriodAmountDayAddsUpItsSubmissions() {
        latestOn(D2, reading(ReadingChannel.PDU, "15", OPERATOR_B, D2, PUMP_SNAPSHOT));
        dayReadings(D2,
                reading(ReadingChannel.PDU, "90", OPERATOR_A, D2, PUMP_SNAPSHOT),
                reading(ReadingChannel.PDU, "30", OPERATOR_A, D2, PUMP_SNAPSHOT),
                reading(ReadingChannel.PDU, "15", OPERATOR_B, D2, PUMP_SNAPSHOT));
        noDayRow(D2);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(savedRows()).singleElement().satisfies(row -> {
            assertThat(row.getWaterQuantity()).isEqualTo(13_500L);
            assertThat(row.getUserId()).isEqualTo(OPERATOR_B);
        });
    }

    @Test
    void oneSubmissionThatCannotBeCalculatedMeansTheDayCannotBe() {
        latestOn(D2, reading(ReadingChannel.PDU, "15", OPERATOR_A, D2, PUMP_SNAPSHOT));
        dayReadings(D2,
                reading(ReadingChannel.PDU, "90", OPERATOR_A, D2, PUMP_SNAPSHOT),
                reading(ReadingChannel.PDU, "30", OPERATOR_A, D2, null),
                reading(ReadingChannel.PDU, "15", OPERATOR_A, D2, PUMP_SNAPSHOT));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(waterQuantityRepository, never()).save(any());
        verify(waterQuantityRepository).deleteReadingDerivedDay(TENANT, SCHEME, D2);
    }

    @Test
    void aDayThatCannotBeCalculatedRemovesItsReadingDerivedTotalAndRecordsBothMetrics() {
        latestOn(D2, reading(ReadingChannel.PDU, "30", OPERATOR_A, D2, null));
        dayReadings(D2, reading(ReadingChannel.PDU, "30", OPERATOR_A, D2, null));
        when(waterQuantityRepository.deleteReadingDerivedDay(TENANT, SCHEME, D2)).thenReturn(1);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(meterRegistry.counter("water_quantity.not_derivable",
                "channel", "3", "reason", "NO_ACTIVE_PUMP").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("water_quantity.day_total_removed", "channel", "3").count())
                .isEqualTo(1.0);
    }

    @Test
    void aDayThatCannotBeCalculatedWithNoTotalToRemoveCountsOnlyTheFirstMetric() {
        // Nothing reading-derived to remove: the day held no row, or only a reason row, which stays.
        latestOn(D2, reading(ReadingChannel.PDU, "30", OPERATOR_A, D2, null));
        dayReadings(D2, reading(ReadingChannel.PDU, "30", OPERATOR_A, D2, null));
        when(waterQuantityRepository.deleteReadingDerivedDay(TENANT, SCHEME, D2)).thenReturn(0);

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        assertThat(meterRegistry.counter("water_quantity.not_derivable",
                "channel", "3", "reason", "NO_ACTIVE_PUMP").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("water_quantity.day_total_removed").counter()).isNull();
    }

    @Test
    void aPeriodAmountDayWithAnUnconfirmedSubmissionIsLeftAlone() {
        latestOn(D2, reading(ReadingChannel.PDU, "15", OPERATOR_A, D2, PUMP_SNAPSHOT));
        dayReadings(D2,
                reading(ReadingChannel.PDU, null, OPERATOR_A, D2, PUMP_SNAPSHOT),
                reading(ReadingChannel.PDU, "15", OPERATOR_A, D2, PUMP_SNAPSHOT));

        service.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(waterQuantityRepository, never()).save(any());
        verify(waterQuantityRepository, never()).deleteReadingDerivedDay(any(), any(), any());
    }

    @Test
    void aPeriodAmountTotalPastBigintLeavesTheRowAloneAndIsCounted() {
        WaterQuantityRecalculationService hugeService = serviceWith(new HugePduCalculator());
        latestOn(D2, reading(ReadingChannel.PDU, "1", OPERATOR_A, D2, PUMP_SNAPSHOT));
        dayReadings(D2,
                reading(ReadingChannel.PDU, "1", OPERATOR_A, D2, PUMP_SNAPSHOT),
                reading(ReadingChannel.PDU, "1", OPERATOR_A, D2, PUMP_SNAPSHOT));

        hugeService.recalculateAfterReading(TENANT, SCHEME, D2);

        verify(waterQuantityRepository, never()).save(any());
        assertThat(meterRegistry.counter("water_quantity.unstorable", "source", "reading").count()).isEqualTo(1.0);
    }

    // ---- deriveDay --------------------------------------------------------------------------

    @Test
    void deriveDay_returnsTheResultWithoutWritingIt() {
        latestOn(D2, reading(ReadingChannel.BFM, "150", OPERATOR_A, D2));
        startingPoint(D2, ReadingChannel.BFM, "100");

        assertThat(service.deriveDay(TENANT, SCHEME, D2)).contains(new Derived(50_000L));
        verify(waterQuantityRepository, never()).save(any());
    }

    @Test
    void deriveDay_isEmptyForADayWithNoReading() {
        assertThat(service.deriveDay(TENANT, SCHEME, D1)).isEmpty();
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private void latestOn(LocalDate date, FactMeterReading reading) {
        when(meterReadingRepository.findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(
                TENANT, SCHEME, date)).thenReturn(Optional.of(reading));
    }

    private void startingPoint(LocalDate date, ReadingChannel channel, String confirmedReading) {
        startingPoint(date, channel, confirmedReading, date.minusDays(1));
    }

    private void startingPoint(LocalDate date, ReadingChannel channel, String confirmedReading, LocalDate readOn) {
        when(meterReadingRepository.findLatestBefore(TENANT, SCHEME, date, channel))
                .thenReturn(Optional.of(reading(channel, confirmedReading, OPERATOR_A, readOn)));
    }

    /**
     * Stubbed rather than left to the mock's default, when a later lookup of the same method is
     * stubbed: strict stubs refuse a call whose arguments match only a stubbing not yet used.
     */
    private void noStartingPoint(LocalDate date, ReadingChannel channel) {
        when(meterReadingRepository.findLatestBefore(TENANT, SCHEME, date, channel)).thenReturn(Optional.empty());
    }

    private void anotherChannelReadBetween(LocalDate after, LocalDate before, ReadingChannel channel, boolean read) {
        when(meterReadingRepository.existsOnAnotherChannelBetween(TENANT, SCHEME, after, before, channel.getCode()))
                .thenReturn(read);
    }

    private void nextReadingDate(LocalDate after, ReadingChannel channel, LocalDate next) {
        when(meterReadingRepository.findNextReadingDate(TENANT, SCHEME, after, channel.getCode()))
                .thenReturn(Optional.of(next));
    }

    /** Stubbed for the same reason as {@link #noStartingPoint}. */
    private void noNextReadingDate(LocalDate after, ReadingChannel channel) {
        when(meterReadingRepository.findNextReadingDate(TENANT, SCHEME, after, channel.getCode()))
                .thenReturn(Optional.empty());
    }

    private void dayReadings(LocalDate date, FactMeterReading... readings) {
        when(meterReadingRepository.findDayReadings(TENANT, SCHEME, date, ReadingChannel.PDU.getCode()))
                .thenReturn(List.of(readings));
    }

    private void noDayRow(LocalDate date) {
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(TENANT, SCHEME, date))
                .thenReturn(Optional.empty());
    }

    private List<FactWaterQuantity> savedRows() {
        ArgumentCaptor<FactWaterQuantity> captor = ArgumentCaptor.forClass(FactWaterQuantity.class);
        verify(waterQuantityRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private static FactMeterReading reading(ReadingChannel channel, String confirmedReading, int userId, LocalDate date) {
        return reading(channel, confirmedReading, userId, date, null);
    }

    private static FactMeterReading reading(ReadingChannel channel, String confirmedReading, int userId,
                                            LocalDate date, CalculationParameters parameters) {
        return FactMeterReading.builder()
                .tenantId(TENANT)
                .schemeId(SCHEME)
                .userId(userId)
                .confirmedReading(confirmedReading == null ? null : new BigDecimal(confirmedReading))
                .channel(channel == null ? null : channel.getCode())
                .readingDate(date)
                .readingAt(date.atTime(8, 0))
                .submissionStatus(1)
                .calculationParameters(parameters)
                .build();
    }

    private static FactWaterQuantity dayRow(LocalDate date, long litres, int userId, int submissionStatus) {
        LocalDateTime earlier = date.atTime(9, 0);
        return FactWaterQuantity.builder()
                .id(99L)
                .tenantId(TENANT)
                .schemeId(SCHEME)
                .userId(userId)
                .waterQuantity(litres)
                .submissionStatus(submissionStatus)
                .date(date)
                .createdAt(earlier)
                .updatedAt(earlier)
                .build();
    }
}
