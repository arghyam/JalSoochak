package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.entity.FactWaterQuantity;
import org.arghyam.jalsoochak.analytics.enums.MeterRegister;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * that into litres. The day is worked out from another channel's reading that day instead when its
 * own channel gives it no total, or one that can't be calculated while another channel's can (see
 * {@link #otherChannelTotal}): a total that can be calculated is never replaced by one that can't.
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

    /**
     * A day's result and the reading it was worked out from, which the day is credited to: the day's
     * latest reading, or another channel's reading that day.
     */
    private record DayResult(FactMeterReading basis, WaterQuantityOutcome outcome) {
    }

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
     * Recalculates {@code readingDate}, the day a reading was just written for. Then recalculates the
     * next date on each meter-index channel ({@link #recalculateNextMeterIndexDays}), whatever
     * {@code readingDate}'s own result was.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recalculateAfterReading(Integer tenantId, Integer schemeId, LocalDate readingDate) {
        recalculateDay(tenantId, schemeId, readingDate, WriteMode.ALWAYS);
        recalculateNextMeterIndexDays(tenantId, schemeId, readingDate);
    }

    /**
     * Recalculates {@code date}, a day a corrected reading has just moved off, and then the next date
     * on each meter-index channel as {@link #recalculateAfterReading} does. Nobody submitted for the
     * day, so it is written only if it changed. A day with no reading left loses the total worked out
     * from its readings; a row holding a reason stays.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recalculateAfterRemoval(Integer tenantId, Integer schemeId, LocalDate date) {
        if (meterReadingRepository
                .findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(tenantId, schemeId, date)
                .isPresent()) {
            recalculateDay(tenantId, schemeId, date, WriteMode.ON_CHANGE);
        } else {
            int removed = waterQuantityRepository.deleteReadingDerivedDay(tenantId, schemeId, date);
            log.info("Removed {} water quantity row(s) for a day with no reading left (tenantId={}, schemeId={}, date={})",
                    removed, tenantId, schemeId, date);
        }
        recalculateNextMeterIndexDays(tenantId, schemeId, date);
    }

    /**
     * For each {@link ReadingKind#METER_INDEX meter-index} channel, the next date with a reading on
     * that channel. That date's amount may now start from {@code date}'s reading, if it is on the same
     * channel, or may no longer be allowed to start from before {@code date}, if it is on another (see
     * {@link #deriveMeterIndexDay}). Only that one date per channel is affected, so this goes no further.
     */
    private void recalculateNextMeterIndexDays(Integer tenantId, Integer schemeId, LocalDate date) {
        Arrays.stream(ReadingChannel.values())
                .filter(channel -> channel.kind().orElse(null) == ReadingKind.METER_INDEX)
                .map(channel -> meterReadingRepository.findNextReadingDate(tenantId, schemeId, date, channel.getCode()))
                .flatMap(Optional::stream)
                .distinct()
                .sorted()
                .forEach(next -> recalculateDay(tenantId, schemeId, next, WriteMode.ON_CHANGE));
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
                .flatMap(this::derive)
                .map(DayResult::outcome);
    }

    private void recalculateDay(Integer tenantId, Integer schemeId, LocalDate date, WriteMode mode) {
        Optional<FactMeterReading> latest = meterReadingRepository
                .findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(tenantId, schemeId, date);
        if (latest.isEmpty()) {
            log.warn("Skipping water quantity update; no stored reading for the day (tenantId={}, schemeId={}, date={})",
                    tenantId, schemeId, date);
            return;
        }
        Optional<DayResult> result;
        try {
            result = derive(latest.get());
        } catch (WaterVolumeOutOfRangeException e) {
            // The reading itself is already saved and stays saved: this runs inside the ingestion
            // transaction, so letting this propagate would roll the reading back, and the consumer
            // would then retry and ultimately drop a submission that was fine to store. Only the
            // derived volume is undecidable, so only the derived volume is skipped.
            rangeReporter.reportUnstorable(e, tenantId, schemeId, date, SOURCE);
            return;
        }
        result.ifPresent(day -> {
            switch (day.outcome()) {
                case Derived derived -> writeDayTotal(day.basis(), derived.litres(), mode);
                case NotDerivable notDerivable -> removeDayTotal(day.basis(), notDerivable.reason());
            }
        });
    }

    private Optional<DayResult> derive(FactMeterReading latest) {
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
            case PERIOD_AMOUNT -> derivePeriodAmountDay(latest, channel, calculator.get())
                    .map(outcome -> preferCalculable(new DayResult(latest, outcome), channel));
        };
    }

    /**
     * The day's latest reading minus the day's starting point, never negative.
     *
     * <p>With no starting point, the day is worked out from another channel's reading that day when one
     * gives the day a total of its own ({@link #otherChannelTotal}). The day a scheme starts reading a
     * new meter then keeps what the old one measured, instead of showing no supply. Otherwise the
     * amount is 0: a running total needs a starting point to be a volume, and treating its absence as
     * 0 would write the whole meter index as one day.
     */
    private Optional<DayResult> deriveMeterIndexDay(FactMeterReading latest, ReadingChannel channel,
                                                    WaterQuantityCalculator calculator) {
        if (latest.getConfirmedReading() == null) {
            log.warn("Skipping water quantity update; the day's latest reading is unconfirmed "
                            + "(tenantId={}, schemeId={}, date={})",
                    latest.getTenantId(), latest.getSchemeId(), latest.getReadingDate());
            return Optional.empty();
        }
        Optional<FactMeterReading> start = startingPoint(latest, channel);
        if (start.isPresent()) {
            return Optional.of(preferCalculable(new DayResult(latest,
                    calculator.calculate(context(latest, channel, increase(latest, start.get())))), channel));
        }
        return otherChannelTotal(latest, channel)
                .or(() -> Optional.of(new DayResult(latest,
                        calculator.calculate(context(latest, channel, BigDecimal.ZERO)))));
    }

    /**
     * The day's own result, unless it can't be calculated and another channel's reading that day gives
     * the day a total that can. A scheme reading two meters then keeps its measured total when the
     * later reading's channel isn't configured yet, whichever order the readings arrived in.
     */
    private DayResult preferCalculable(DayResult own, ReadingChannel channel) {
        if (!(own.outcome() instanceof NotDerivable notDerivable)) {
            return own;
        }
        return otherChannelTotal(own.basis(), channel)
                .filter(other -> other.outcome() instanceof Derived)
                .map(other -> {
                    ReadingChannel otherChannel = ReadingChannel.fromCode(other.basis().getChannel());
                    log.warn("Water quantity cannot be calculated: {} (channel={}, tenantId={}, schemeId={}, date={}); "
                                    + "the day's total is taken from channel={}",
                            notDerivable.reason(), channel, own.basis().getTenantId(), own.basis().getSchemeId(),
                            own.basis().getReadingDate(), otherChannel);
                    meterRegistry.counter("water_quantity.channel_fallback",
                                    "channel", channelTag(channel),
                                    "reason", notDerivable.reason().name())
                            .increment();
                    return other;
                })
                .orElse(own);
    }

    /** Subtracted at the readings' own precision; the calculator rounds once, on the way into litres. */
    private static BigDecimal increase(FactMeterReading reading, FactMeterReading start) {
        return reading.getConfirmedReading().subtract(start.getConfirmedReading()).max(BigDecimal.ZERO);
    }

    /**
     * The day's result from another channel's latest reading that gives the day a total of its own: a
     * meter-index reading with a starting point, or a period-amount channel's submissions. A channel
     * with no calculator, or an unconfirmed reading, gives none. Channels are tried latest first; the
     * first total that can be calculated wins, and failing that, the first that can't, so the day is
     * never given a 0 that is silently too low.
     */
    private Optional<DayResult> otherChannelTotal(FactMeterReading latest, ReadingChannel channel) {
        DayResult firstNotDerivable = null;
        for (FactMeterReading reading : latestOnEachOtherChannel(latest, channel)) {
            Optional<DayResult> total = ownTotal(reading);
            if (total.isEmpty()) {
                continue;
            }
            if (total.get().outcome() instanceof Derived) {
                return total;
            }
            if (firstNotDerivable == null) {
                firstNotDerivable = total.get();
            }
        }
        return Optional.ofNullable(firstNotDerivable);
    }

    /** Each other channel's latest reading on the day, latest first. */
    private Collection<FactMeterReading> latestOnEachOtherChannel(FactMeterReading latest, ReadingChannel channel) {
        Map<ReadingChannel, FactMeterReading> latestByChannel = new LinkedHashMap<>();
        meterReadingRepository.findByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(
                        latest.getTenantId(), latest.getSchemeId(), latest.getReadingDate())
                .forEach(reading -> latestByChannel.putIfAbsent(ReadingChannel.fromCode(reading.getChannel()), reading));
        latestByChannel.remove(channel);
        return latestByChannel.values();
    }

    private Optional<DayResult> ownTotal(FactMeterReading reading) {
        ReadingChannel channel = ReadingChannel.fromCode(reading.getChannel());
        Optional<ReadingKind> kind = channel.kind();
        Optional<WaterQuantityCalculator> calculator = calculatorRegistry.resolve(channel);
        if (kind.isEmpty() || calculator.isEmpty()) {
            return Optional.empty();
        }
        Optional<WaterQuantityOutcome> outcome = switch (kind.get()) {
            case METER_INDEX -> reading.getConfirmedReading() == null
                    ? Optional.empty()
                    : startingPoint(reading, channel).map(start ->
                            calculator.get().calculate(context(reading, channel, increase(reading, start))));
            case PERIOD_AMOUNT -> derivePeriodAmountDay(reading, channel, calculator.get());
        };
        return outcome.map(result -> new DayResult(reading, result));
    }

    /**
     * The latest reading on {@code reading}'s channel before its day, unless it is on another
     * {@link MeterRegister register} or another channel has a reading dated in between.
     *
     * <p>A reading on another register is another quantity (kWh against kVAh), so the switch is treated
     * like a new meter: the day has no starting point. Dates read on another channel were counted
     * there, so measuring across them would count their water twice.
     */
    private Optional<FactMeterReading> startingPoint(FactMeterReading reading, ReadingChannel channel) {
        MeterRegister register = MeterRegister.of(reading.getSubmittedUnit());
        return meterReadingRepository
                .findLatestBefore(reading.getTenantId(), reading.getSchemeId(), reading.getReadingDate(), channel)
                .filter(previous -> MeterRegister.of(previous.getSubmittedUnit()) == register)
                .filter(previous -> !meterReadingRepository.existsOnAnotherChannelBetween(
                        reading.getTenantId(), reading.getSchemeId(),
                        previous.getReadingDate(), reading.getReadingDate(), channel.getCode()));
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
                .register(MeterRegister.of(reading.getSubmittedUnit()))
                .parameters(reading.getCalculationParameters())
                .build();
    }

    /**
     * Writes the day's total. The day is credited to the operator and status of the reading it was
     * worked out from, not the triggering event's: a follow-up day has no event of its own, and the
     * SO/SDO pump-operator list matches operators on this row's {@code user_id}.
     */
    private void writeDayTotal(FactMeterReading basis, long litres, WriteMode mode) {
        Integer tenantId = basis.getTenantId();
        Integer schemeId = basis.getSchemeId();
        LocalDate date = basis.getReadingDate();
        Integer userId = basis.getUserId();
        Integer submissionStatus = Objects.requireNonNullElse(
                basis.getSubmissionStatus(), SubmissionStatus.SUBMITTED.getCode());

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
    private void removeDayTotal(FactMeterReading basis, WaterQuantityOutcome.Reason reason) {
        ReadingChannel channel = ReadingChannel.fromCode(basis.getChannel());
        log.warn("Water quantity cannot be calculated: {} (channel={}, tenantId={}, schemeId={}, date={}); "
                        + "the day gets no total",
                reason, channel, basis.getTenantId(), basis.getSchemeId(), basis.getReadingDate());
        meterRegistry.counter("water_quantity.not_derivable",
                        "channel", channelTag(channel),
                        "reason", reason.name())
                .increment();
        int removed = waterQuantityRepository.deleteReadingDerivedDay(
                basis.getTenantId(), basis.getSchemeId(), basis.getReadingDate());
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
