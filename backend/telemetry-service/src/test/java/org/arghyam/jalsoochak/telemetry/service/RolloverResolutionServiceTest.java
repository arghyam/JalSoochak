package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.dto.response.RolloverPosition;
import org.arghyam.jalsoochak.telemetry.repository.DailyConfirmedReading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link RolloverResolutionService}. No Spring context, no DB — the service is a
 * pure function of (ocr, history, previousConfirmed, isMeterReplaced) plus the injected kill-switch.
 */
class RolloverResolutionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RolloverResolutionService enabledService() {
        return new RolloverResolutionService(true, MAPPER);
    }

    // ── Overriding cases ────────────────────────────────────────────────────────────────────────────

    @Test
    void highOrderFlipCorrectedByHistory() {
        // Steady ~10/day consumption, anchor 140. Model read "0250" (250, an implausible +110 jump);
        // the alternate digit at the hundreds place gives "0150" (150, a normal +10) → resolver overrides.
        FlowVisionResult ocr = ocr("0250", "250", false,
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), false);

        assertEquals(RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("150")));
        assertNotNull(resolved.auditJson());
    }

    @Test
    void twoSignificantPositionsCartesianPicksBestCombination() {
        // Two ambiguous high-order positions → 4 candidates. Only the pos-2 alternate ("1040", +10)
        // clears monotonicity + the cap; the others overshoot far beyond it.
        FlowVisionResult ocr = ocr("1140", "1140", false,
                new RolloverPosition(1, 1, bd("0.9"), 2, bd("0.1")),
                new RolloverPosition(2, 1, bd("0.6"), 0, bd("0.4")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(1000, 1010, 1020, 1030), bd("1030"), false);

        assertEquals(RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("1040")));
    }

    @Test
    void redLastDigitDecimalCandidateResolved() {
        // Decimal meter (red last digit): band ~1.0/day, anchor 53.0. Model "0640" → 64.0 (+11,
        // implausible); alternate "0540" → 54.0 (+1.0, normal) → override with the decimal shift applied.
        FlowVisionResult ocr = ocr("0640", "64.0", true,
                new RolloverPosition(2, 6, bd("0.55"), 5, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, historyD(50.0, 51.0, 52.0, 53.0), bd("53.0"), false);

        assertEquals(RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("54.0")));
    }

    @Test
    void negativeBoundaryDeltaExcludedFromBand() {
        // A negative daily delta (meter replacement / correction) at 1020 → 1005 is dropped from the band,
        // leaving a clean ~10/day band that still resolves "1260" (+210) down to "1060" (+10).
        List<DailyConfirmedReading> history = history(1000, 1010, 1020, 1005, 1030, 1040, 1050);
        FlowVisionResult ocr = ocr("1260", "1260", false,
                new RolloverPosition(2, 2, bd("0.55"), 0, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history, bd("1050"), false);

        assertEquals(RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("1060")));
    }

    // ── Conservative / short-circuit cases → keep the model value (source 0) ─────────────────────────

    @Test
    void nearTieKeepsModelValue() {
        // Both siblings ("0140" and "0150") are plausible (deltas 0 and 10, both ≤ μ) so the domain
        // penalty is zero for each and only the tiny confidence gap separates them (< margin) → keep model.
        FlowVisionResult ocr = ocr("0140", "140", false,
                new RolloverPosition(3, 4, bd("0.5"), 5, bd("0.55")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("140")));
        assertNull(resolved.auditJson());
    }

    @Test
    void lowOrderPositionBelowSignificanceKeepsModelValue() {
        // A units-place flip on a scheme consuming ~100/day swings the reading by 1 (< significance) →
        // no significant position → model value.
        FlowVisionResult ocr = ocr("1400", "1400", false,
                new RolloverPosition(4, 0, bd("0.6"), 1, bd("0.4")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(1000, 1100, 1200, 1300), bd("1300"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("1400")));
    }

    @Test
    void allCandidatesFailMonotonicityKeepsModelValue() {
        // Both siblings ("0200"/"0100") sit far below the anchor (950) → all fail monotonicity → never
        // invent, keep the model value.
        FlowVisionResult ocr = ocr("0200", "200", false,
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(800, 850, 900, 950), bd("950"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("200")));
    }

    @Test
    void tooFewValidDeltasKeepsModelValue() {
        // A single historical day yields zero deltas (< MIN_DELTAS) → new-scheme fallback → model value.
        FlowVisionResult ocr = ocr("0250", "250", false,
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100), bd("100"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void meterReplacedKeepsModelValue() {
        FlowVisionResult ocr = ocr("0250", "250", false,
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), true);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void firstEverReadingKeepsModelValue() {
        FlowVisionResult ocr = ocr("0250", "250", false,
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), null, false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void noRolloverKeepsModelValue() {
        FlowVisionResult ocr = FlowVisionResult.builder()
                .adjustedReading(bd("250"))
                .rawMeterReading("0250")
                .redLastDigit(false)
                .hasRollover(false)
                .rolloverPositions(List.of())
                .build();

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void killSwitchOffKeepsModelValue() {
        RolloverResolutionService disabled = new RolloverResolutionService(false, MAPPER);
        assertTrue(!disabled.isEnabled());
        FlowVisionResult ocr = ocr("0250", "250", false,
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = disabled
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private static FlowVisionResult ocr(String rawReading, String adjusted, boolean redLastDigit,
                                        RolloverPosition... positions) {
        return FlowVisionResult.builder()
                .adjustedReading(bd(adjusted))
                .rawMeterReading(rawReading)
                .redLastDigit(redLastDigit)
                .hasRollover(true)
                .rolloverPositions(List.of(positions))
                .build();
    }

    /** Whole-number daily readings, oldest-first; returned most-recent-first to mirror the repository. */
    private static List<DailyConfirmedReading> history(long... readings) {
        List<DailyConfirmedReading> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < readings.length; i++) {
            list.add(new DailyConfirmedReading(base.plusDays(i), BigDecimal.valueOf(readings[i])));
        }
        Collections.reverse(list);
        return list;
    }

    /** Decimal daily readings, oldest-first; returned most-recent-first to mirror the repository. */
    private static List<DailyConfirmedReading> historyD(double... readings) {
        List<DailyConfirmedReading> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < readings.length; i++) {
            list.add(new DailyConfirmedReading(base.plusDays(i), BigDecimal.valueOf(readings[i])));
        }
        Collections.reverse(list);
        return list;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
