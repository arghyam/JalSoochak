package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
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
        OcrReadingResult ocr = ocr("0250", "250",
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
        OcrReadingResult ocr = ocr("1140", "1140",
                new RolloverPosition(1, 1, bd("0.9"), 2, bd("0.1")),
                new RolloverPosition(2, 1, bd("0.6"), 0, bd("0.4")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(1000, 1010, 1020, 1030), bd("1030"), false);

        assertEquals(RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("1040")));
    }

    @Test
    void decimalReadingCandidateResolved() {
        // Decimal meter, point placed by the OCR provider: band ~1.0/day, anchor 53.0. Model "064.0" → 64.0
        // (+11, implausible); alternate "054.0" → 54.0 (+1.0, normal) → override, keeping the point's place.
        OcrReadingResult ocr = ocr("064.0", "64.0",
                new RolloverPosition(2, 6, bd("0.55"), 5, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, historyD(50.0, 51.0, 52.0, 53.0), bd("53.0"), false);

        assertEquals(RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("54.0")));
    }

    @Test
    void twoDecimalPlaceReadingCandidateResolved() {
        // Two digits after the point: band ~1.5/day, anchor 1253.00. Model "01264.48" → 1264.48 (+11.48,
        // implausible); the tens-place alternate gives "01254.48" → 1254.48 (+1.48, normal) → override.
        OcrReadingResult ocr = ocr("01264.48", "1264.48",
                new RolloverPosition(4, 6, bd("0.55"), 5, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, historyD(1248.5, 1250.0, 1251.5, 1253.0), bd("1253.0"), false);

        assertEquals(RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("1254.48")));
    }

    @Test
    void rolloverPositionsCountDigitsOnlySkippingThePoint() {
        // Position 7 of "030701.1" is its last digit, the one after the point: band ~0.2/day, anchor 30700.8.
        // Model 30701.1 (+0.3) overshoots the cap; the alternate 0 gives 30701.0 (+0.2, normal) → override.
        OcrReadingResult ocr = ocr("030701.1", "30701.1",
                new RolloverPosition(7, 1, bd("0.55"), 0, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, historyD(30700.2, 30700.4, 30700.6, 30700.8), bd("30700.8"), false);

        assertEquals(RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("30701.0")));
    }

    @Test
    void negativeBoundaryDeltaExcludedFromBand() {
        // A negative daily delta (meter replacement / correction) at 1020 → 1005 is dropped from the band,
        // leaving a clean ~10/day band that still resolves "1260" (+210) down to "1060" (+10).
        List<DailyConfirmedReading> history = history(1000, 1010, 1020, 1005, 1030, 1040, 1050);
        OcrReadingResult ocr = ocr("1260", "1260",
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
        OcrReadingResult ocr = ocr("0140", "140",
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
        OcrReadingResult ocr = ocr("1400", "1400",
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
        OcrReadingResult ocr = ocr("0200", "200",
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(800, 850, 900, 950), bd("950"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("200")));
    }

    @Test
    void tooFewValidDeltasKeepsModelValue() {
        // A single historical day yields zero deltas (< MIN_DELTAS) → new-scheme fallback → model value.
        OcrReadingResult ocr = ocr("0250", "250",
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100), bd("100"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void meterReplacedKeepsModelValue() {
        OcrReadingResult ocr = ocr("0250", "250",
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), true);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void firstEverReadingKeepsModelValue() {
        OcrReadingResult ocr = ocr("0250", "250",
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), null, false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void noRolloverKeepsModelValue() {
        OcrReadingResult ocr = OcrReadingResult.builder()
                .adjustedReading(bd("250"))
                .rawMeterReading("0250")
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
        OcrReadingResult ocr = ocr("0250", "250",
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = disabled
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void duplicateRolloverPositionKeepsModelValue() {
        // Two entries target the same raw position — malformed metadata. Rather than let both choice bits
        // write digit index 2 (which would collapse candidates and double-count confidence), the resolver
        // falls back to the model value. Without the guard this same input resolves to 150.
        OcrReadingResult ocr = ocr("0250", "250",
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")),
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void selectedDigitInconsistentWithRawReadingKeepsModelValue() {
        // The selected digit (7) disagrees with the raw string digit at position 2 ('2') — inconsistent
        // metadata, so the resolver declines to reason positionally and keeps the model value.
        OcrReadingResult ocr = ocr("0250", "250",
                new RolloverPosition(2, 7, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    @Test
    void rawReadingWithASecondPointKeepsModelValue() {
        // A raw reading with two points has no single place for the point, so the resolver declines to
        // reason positionally and keeps the model value.
        OcrReadingResult ocr = ocr("02.5.0", "250",
                new RolloverPosition(2, 2, bd("0.55"), 1, bd("0.45")));

        RolloverResolutionService.ResolvedReading resolved = enabledService()
                .resolve(ocr, history(100, 110, 120, 130, 140), bd("140"), false);

        assertEquals(RolloverResolutionService.SOURCE_AS_EXTRACTED, resolved.source());
        assertEquals(0, resolved.confirmedReading().compareTo(bd("250")));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private static OcrReadingResult ocr(String rawReading, String adjusted, RolloverPosition... positions) {
        return OcrReadingResult.builder()
                .adjustedReading(bd(adjusted))
                .rawMeterReading(rawReading)
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
