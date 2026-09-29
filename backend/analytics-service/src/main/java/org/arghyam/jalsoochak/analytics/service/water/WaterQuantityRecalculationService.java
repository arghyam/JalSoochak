package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.entity.FactWaterQuantity;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.enums.ReadingKind;
import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.repository.FactWaterQuantityRepository;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.Derived;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.NotDerivable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Works out a day's water quantity from the readings stored in {@code fact_meter_reading_table} and
 * writes it to {@code fact_water_quantity_table}.
 *
 * <p>Everything is read back from the table rather than taken from the event that triggered it, so
 * the result depends only on which readings are stored, not on the order they arrived in. The same
 * rule, for BFM, is {@code db/scripts/recompute_water_quantity.sql};
 * {@code WaterQuantityBackfillParityIntegrationTest} keeps the two identical.
 *
 * <p>A day's channel is the channel of its latest reading (legacy {@code NULL} is BFM). The
 * channel's {@link ReadingKind} decides the day's amount; its {@link WaterQuantityCalculator} turns
 * that into litres.
 *
 * <p>Callers must hold {@link org.arghyam.jalsoochak.analytics.repository.FactIngestionRepository#lockScheme}
 * for the scheme, in the same transaction; recalculating therefore requires one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WaterQuantityRecalculationService {

    /** {@link WaterQuantityRangeReporter} source tag for this write path. */
    private static final String SOURCE = "reading";

    private final FactMeterReadingRepository meterReadingRepository;
    private final FactWaterQuantityRepository waterQuantityRepository;
    private final WaterQuantityCalculatorRegistry calculatorRegistry;
    private final WaterQuantityRangeReporter rangeReporter;
    private final MeterRegistry meterRegistry;

    /** Whether a recalculated day is written even when its row would not change. */
    private enum WriteMode {
        /** The day a reading was written for: always saved, so its {@code updated_at} moves. */
        ALWAYS,
        /**
         * A day recalculated as a follow-up. The SO/SDO pump-operator list shows the row's
         * {@code updated_at} as the operator's last submission, so a day nobody touched keeps it.
         */
        ON_CHANGE
    }

    /**
     * Recalculates {@code readingDate}, the day a reading was just written for. When the written
     * reading's channel is a {@link ReadingKind#METER_INDEX meter index}, then also recalculates the
     * next date with a reading on that channel, whatever {@code readingDate}'s own result was: its
     * amount starts from this day's reading. Only that one date does, so the follow-up goes no
     * further.
     *
     * @param writtenChannel the channel of the reading that was written, not the day's channel
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recalculateAfterReading(Integer tenantId, Integer schemeId, LocalDate readingDate,
                                        ReadingChannel writtenChannel) {
        recalculateDay(tenantId, schemeId, readingDate, WriteMode.ALWAYS);
        if (writtenChannel.kind().orElse(null) == ReadingKind.METER_INDEX) {
            meterReadingRepository
                    .findNextReadingDate(tenantId, schemeId, readingDate, writtenChannel.getCode())
                    .ifPresent(next -> recalculateDay(tenantId, schemeId, next, WriteMode.ON_CHANGE));
        }
    }

    /**
     * The day's result from its stored readings, without writing it.
     *
     * @return empty when the day is left alone: it has no reading, no confirmed amount, or its
     *         channel has no calculator
     * @throws WaterVolumeOutOfRangeException if the day's litres do not fit the {@code BIGINT} column
     */
    public Optional<WaterQuantityOutcome> deriveDay(Integer tenantId, Integer schemeId, LocalDate date) {
        return meterReadingRepository
                .findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(tenantId, schemeId, date)
                .flatMap(this::derive);
    }

    private void recalculateDay(Integer tenantId, Integer schemeId, LocalDate date, WriteMode mode) {
        Optional<FactMeterReading> latest = meterReadingRepository
                .findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(tenantId, schemeId, date);
        if (latest.isEmpty()) {
            log.warn("Skipping water quantity update; no stored reading for the day (tenantId={}, schemeId={}, date={})",
                    tenantId, schemeId, date);
            return;
        }
        Optional<WaterQuantityOutcome> outcome;
        try {
            outcome = derive(latest.get());
        } catch (WaterVolumeOutOfRangeException e) {
            // The reading itself is already saved and stays saved: this runs inside the ingestion
            // transaction, so letting this propagate would roll the reading back, and the consumer
            // would then retry and ultimately drop a submission that was fine to store. Only the
            // derived volume is undecidable, so only the derived volume is skipped.
            rangeReporter.reportUnstorable(e, tenantId, schemeId, date, SOURCE);
            return;
        }
        outcome.ifPresent(result -> {
            switch (result) {
                case Derived derived -> writeDayTotal(latest.get(), derived.litres(), mode);
                case NotDerivable notDerivable -> removeDayTotal(latest.get(), notDerivable.reason());
            }
        });
    }

    private Optional<WaterQuantityOutcome> derive(FactMeterReading latest) {
        ReadingChannel channel = ReadingChannel.fromCode(latest.getChannel());
        Optional<ReadingKind> kind = channel.kind();
        Optional<WaterQuantityCalculator> calculator = calculatorRegistry.resolve(channel);
        if (kind.isEmpty() || calculator.isEmpty()) {
            // The day's existing row is left as it is: a channel with no calculator is not a day that
            // cannot be calculated, and must never be derived with another channel's calculator.
            log.warn("Skipping water quantity update; no calculator registered for channel={} "
                            + "(tenantId={}, schemeId={}, date={})",
                    channel, latest.getTenantId(), latest.getSchemeId(), latest.getReadingDate());
            meterRegistry.counter("water_quantity.calculator.missing", "channel", channelTag(channel))
                    .increment();
            return Optional.empty();
        }
        return switch (kind.get()) {
            case METER_INDEX -> deriveMeterIndexDay(latest, channel, calculator.get());
            case PERIOD_AMOUNT -> derivePeriodAmountDay(latest, channel, calculator.get());
        };
    }

    /**
     * The day's latest reading minus the latest reading on the same channel before the day, never
     * negative. With no earlier reading the amount is 0: a running total needs a starting point to
     * be a volume, and treating its absence as 0 would write the whole meter index as one day.
     */
    private Optional<WaterQuantityOutcome> deriveMeterIndexDay(FactMeterReading latest, ReadingChannel channel,
                                                              WaterQuantityCalculator calculator) {
        BigDecimal current = latest.getConfirmedReading();
        if (current == null) {
            log.warn("Skipping water quantity update; the day's latest reading is unconfirmed "
                            + "(tenantId={}, schemeId={}, date={})",
                    latest.getTenantId(), latest.getSchemeId(), latest.getReadingDate());
            return Optional.empty();
        }
        // Subtract at the readings' own precision; the calculator rounds once, on the way into litres.
        BigDecimal amount = meterReadingRepository
                .findLatestBefore(latest.getTenantId(), latest.getSchemeId(), latest.getReadingDate(), channel)
                .map(FactMeterReading::getConfirmedReading)
                .map(previous -> current.subtract(previous).max(BigDecimal.ZERO))
                .orElse(BigDecimal.ZERO);
        return Optional.of(calculator.calculate(context(latest, channel, amount)));
    }

    /**
     * The sum of each of the day's submissions on the channel, each turned into litres with its own
     * amount and snapshot. One submission that cannot be turned into litres makes the whole day
     * impossible to calculate, so the total is never silently too low.
     */
    private Optional<WaterQuantityOutcome> derivePeriodAmountDay(FactMeterReading latest, ReadingChannel channel,
                                                                WaterQuantityCalculator calculator) {
        List<FactMeterReading> submissions = meterReadingRepository.findDayReadings(
                latest.getTenantId(), latest.getSchemeId(), latest.getReadingDate(), channel.getCode());
        long total = 0L;
        for (FactMeterReading submission : submissions) {
            BigDecimal amount = submission.getConfirmedReading();
            if (amount == null) {
                log.warn("Skipping water quantity update; a submission of the day is unconfirmed "
                                + "(tenantId={}, schemeId={}, date={})",
                        latest.getTenantId(), latest.getSchemeId(), latest.getReadingDate());
                return Optional.empty();
            }
            switch (calculator.calculate(context(submission, channel, amount))) {
                case NotDerivable notDerivable -> {
                    return Optional.of(notDerivable);
                }
                case Derived derived -> total = addLitres(total, derived.litres());
            }
        }
        return Optional.of(WaterQuantityOutcome.derived(total));
    }

    private static long addLitres(long total, long litres) {
        try {
            return Math.addExact(total, litres);
        } catch (ArithmeticException e) {
            throw new WaterVolumeOutOfRangeException(
                    BigDecimal.valueOf(total).add(BigDecimal.valueOf(litres)), "L");
        }
    }

    private static WaterQuantityContext context(FactMeterReading reading, ReadingChannel channel, BigDecimal amount) {
        return WaterQuantityContext.builder()
                .tenantId(reading.getTenantId())
                .schemeId(reading.getSchemeId())
                .readingDate(reading.getReadingDate())
                .channel(channel)
                .amount(amount)
                .parameters(reading.getCalculationParameters())
                .build();
    }

    /**
     * Writes the day's total. The day is credited to its latest reading's operator and status, not
     * the triggering event's: a follow-up day has no event of its own, and the SO/SDO pump-operator
     * list matches operators on this row's {@code user_id}.
     */
    private void writeDayTotal(FactMeterReading latest, long litres, WriteMode mode) {
        Integer tenantId = latest.getTenantId();
        Integer schemeId = latest.getSchemeId();
        LocalDate date = latest.getReadingDate();
        Integer userId = latest.getUserId();
        Integer submissionStatus = Objects.requireNonNullElse(
                latest.getSubmissionStatus(), SubmissionStatus.SUBMITTED.getCode());

        Optional<FactWaterQuantity> existing = waterQuantityRepository
                .findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(tenantId, schemeId, date);
        if (mode == WriteMode.ON_CHANGE
                && existing.filter(row -> alreadyHolds(row, litres, userId, submissionStatus)).isPresent()) {
            return;
        }
        rangeReporter.reportIfImplausible(litres, tenantId, schemeId, date, SOURCE);
        LocalDateTime now = LocalDateTime.now();
        FactWaterQuantity row = existing
                .map(found -> {
                    found.setWaterQuantity(litres);
                    found.setUserId(userId);
                    found.setSubmissionStatus(submissionStatus);
                    found.setOutageReason(null);
                    found.setNonSubmissionReason(null);
                    found.setUpdatedAt(now);
                    return found;
                })
                .orElseGet(() -> FactWaterQuantity.builder()
                        .tenantId(tenantId)
                        .schemeId(schemeId)
                        .userId(userId)
                        .waterQuantity(litres)
                        .submissionStatus(submissionStatus)
                        .date(date)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
        waterQuantityRepository.save(row);
    }

    private static boolean alreadyHolds(FactWaterQuantity row, long litres, Integer userId, Integer submissionStatus) {
        return Objects.equals(row.getWaterQuantity(), litres)
                && Objects.equals(row.getUserId(), userId)
                && Objects.equals(row.getSubmissionStatus(), submissionStatus)
                && row.getOutageReason() == null
                && row.getNonSubmissionReason() == null;
    }

    /**
     * A day that cannot be calculated has no total. A total worked out from readings earlier is
     * removed, so the day ends up the same whatever order its readings arrived in; a row holding an
     * outage, non-submission or meter-change reason stays.
     */
    private void removeDayTotal(FactMeterReading latest, WaterQuantityOutcome.Reason reason) {
        ReadingChannel channel = ReadingChannel.fromCode(latest.getChannel());
        log.warn("Water quantity cannot be calculated: {} (channel={}, tenantId={}, schemeId={}, date={}); "
                        + "the day gets no total",
                reason, channel, latest.getTenantId(), latest.getSchemeId(), latest.getReadingDate());
        meterRegistry.counter("water_quantity.not_derivable",
                        "channel", channelTag(channel),
                        "reason", reason.name())
                .increment();
        int removed = waterQuantityRepository.deleteReadingDerivedDay(
                latest.getTenantId(), latest.getSchemeId(), latest.getReadingDate());
        if (removed > 0) {
            meterRegistry.counter("water_quantity.day_total_removed", "channel", channelTag(channel))
                    .increment();
        }
    }

    /** The numeric channel code, as {@code water_quantity.calculator.missing} has always tagged it. */
    private static String channelTag(ReadingChannel channel) {
        return String.valueOf(channel.getCode());
    }
}
