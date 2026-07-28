package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.dto.response.RolloverPosition;
import org.arghyam.jalsoochak.telemetry.repository.DailyConfirmedReading;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves FlowVision rollover-digit ambiguity for a meter reading before it is confirmed.
 *
 * <p>A mechanical digit wheel caught mid-turn is ambiguous between two adjacent values. FlowVision
 * reports a higher-confidence {@code selectedDigit} (already used to build {@code meterReading}) and a
 * runner-up {@code alternateDigit} per ambiguous position. The confidence gap is usually tiny and thus
 * weak evidence, so this service cross-checks each candidate reading against the scheme's own recent
 * consumption pattern (a robust median/MAD band with a Tukey overshoot cap) and the physical constraint
 * that a meter never decreases. It <strong>conservatively</strong> overrides the model's pick only when
 * a sibling candidate is clearly more plausible.
 *
 * <p>The class is a pure function of its inputs (only the kill-switch is injected) so it is fully
 * unit-testable without a Spring context.
 */
@Service
@Slf4j
public class RolloverResolutionService {

    /** {@code confirmed_reading_source} value: reading is the model's pick, unchanged. */
    public static final int SOURCE_AS_EXTRACTED = 0;
    /** {@code confirmed_reading_source} value: reading was resolved to a sibling rollover candidate. */
    public static final int SOURCE_ROLLOVER_RESOLVED = 1;

    // ── v1 tuning constants (documented; only the kill-switch is externalised) ──────────────────────
    /** Trailing daily-delta window size. */
    static final int WINDOW = 14;
    /** Minimum valid deltas to attempt resolution (else accept model value — new scheme). */
    static final int MIN_DELTAS = 2;
    /** Minimum valid deltas for stable quartiles; below this the cap falls back to a MAD fence. */
    static final int MIN_DELTAS_FOR_QUARTILES = 4;
    /** Tukey fence multiplier (Q3 + k·IQR). */
    static final double TUKEY_K = 1.5;
    /** Small positive floor on σ (as a fraction of μ) so a perfectly flat scheme keeps σ &gt; 0. */
    static final double FLOOR_FRACTION = 0.05;
    /** A position is "significant" only if its flip swings the reading by ≥ this fraction of μ. */
    static final double SIGNIFICANCE_FRACTION = 0.05;
    /** Score weight on OCR confidence. */
    static final double W_CONF = 1.0;
    /** Score weight on the one-sided consumption-plausibility penalty. */
    static final double W_DOM = 1.0;
    /** Minimum score advantage over the model candidate required to override (conservative). */
    static final double MARGIN_THRESHOLD = 1.0;
    /** MAD → σ normalisation constant (consistency factor for a normal distribution). */
    private static final double MAD_SCALE = 1.4826;
    /** Confidence used when FlowVision omits a per-digit confidence. */
    private static final double DEFAULT_CONFIDENCE = 0.5;
    /** Floor on confidence before taking a logarithm (avoids ln(0) = -∞). */
    private static final double MIN_CONFIDENCE = 1e-6;
    /** Safety bound on the number of significant positions, so the Cartesian search stays tiny. */
    private static final int MAX_SIGNIFICANT_POSITIONS = 6;

    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public RolloverResolutionService(
            @Value("${flowvision.rollover.resolution.enabled:false}") boolean enabled,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.objectMapper = objectMapper;
    }

    /**
     * Whether the resolver is active. Callers use this to gate the trailing-history DB fetch so the
     * common (no-rollover, or kill-switch-off) path adds zero extra round-trips.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param ocr               the FlowVision result (model pick + rollover metadata).
     * @param history           one confirmed reading per calendar day over the trailing window,
     *                          most-recent-first (as returned by
     *                          {@code findRecentDailyConfirmedReadings}).
     * @param previousConfirmed the latest confirmed reading (monotonicity fallback anchor);
     *                          {@code null} for the first-ever reading.
     * @param isMeterReplaced   whether this submission is itself a meter replacement (fresh baseline).
     * @return the resolved reading plus its provenance {@code source} and an optional audit blob.
     */
    public ResolvedReading resolve(FlowVisionResult ocr,
                                   List<DailyConfirmedReading> history,
                                   BigDecimal previousConfirmed,
                                   boolean isMeterReplaced) {
        BigDecimal modelValue = ocr != null ? ocr.getAdjustedReading() : null;

        // ── Short-circuits → return model value, source 0 (byte-identical to legacy behaviour) ──────
        if (!enabled
                || ocr == null
                || modelValue == null
                || !ocr.isHasRollover()
                || ocr.getRolloverPositions() == null
                || ocr.getRolloverPositions().isEmpty()
                || isMeterReplaced
                || previousConfirmed == null) {
            return asExtracted(modelValue);
        }

        String rawReading = ocr.getRawMeterReading();
        if (rawReading == null || !rawReading.chars().allMatch(Character::isDigit)) {
            // Cannot reason positionally on a non-digit / missing raw reading.
            return asExtracted(modelValue);
        }
        int length = rawReading.length();

        // ── 1. Consumption band from the daily history ─────────────────────────────────────────────
        double[] deltas = validDailyDeltas(history);
        if (deltas.length < MIN_DELTAS) {
            return asExtracted(modelValue);
        }
        double mu = median(deltas);
        double mad = medianAbsoluteDeviation(deltas, mu);
        double sigma = Math.max(MAD_SCALE * mad, FLOOR_FRACTION * mu);
        double upperCap = upperConsumptionCap(deltas, mu, sigma, mad);

        // Robust monotonicity anchor (§7): the meter only advances, so the max confirmed reading over
        // the recent window is a spike-resistant lower bound for the true meter value.
        BigDecimal anchor = robustAnchor(previousConfirmed, history);

        // ── 2. Significance filter: keep only positions whose flip meaningfully moves the reading ───
        double scale = ocr.isRedLastDigit() ? 0.1 : 1.0;
        double swingThreshold = SIGNIFICANCE_FRACTION * mu;
        List<RolloverPosition> significant = new ArrayList<>();
        for (RolloverPosition pos : ocr.getRolloverPositions()) {
            if (pos.position() < 1 || pos.position() > length) {
                continue; // position outside the raw string — ignore
            }
            double swing = Math.pow(10, length - pos.position()) * scale;
            if (swing >= swingThreshold) {
                significant.add(pos);
            }
        }
        if (significant.isEmpty()) {
            return asExtracted(modelValue);
        }
        if (significant.size() > MAX_SIGNIFICANT_POSITIONS) {
            // Keep the highest-order (largest-swing) positions; they dominate the error.
            significant.sort(Comparator.comparingInt(RolloverPosition::position));
            significant = new ArrayList<>(significant.subList(0, MAX_SIGNIFICANT_POSITIONS));
        }

        // ── 3./4./5. Enumerate candidates, hard-filter, score ──────────────────────────────────────
        int combos = 1 << significant.size();
        List<Candidate> survivors = new ArrayList<>();
        Candidate modelCandidate = null;
        for (int mask = 0; mask < combos; mask++) {
            Candidate candidate = buildCandidate(rawReading, significant, mask, ocr.isRedLastDigit());
            if (candidate.isModel) {
                modelCandidate = candidate;
            }
            BigDecimal impliedDelta = candidate.reading.subtract(anchor);
            if (impliedDelta.signum() < 0) {
                continue; // monotonicity: a meter never decreases (asymmetric — only overshoot is capped)
            }
            if (impliedDelta.doubleValue() > upperCap) {
                continue; // implausible overshoot beyond the data-driven Tukey cap
            }
            candidate.score = W_CONF * candidate.confidenceLogSum
                    + W_DOM * domainPenalty(impliedDelta.doubleValue(), mu, sigma);
            survivors.add(candidate);
        }
        if (survivors.isEmpty()) {
            return asExtracted(modelValue); // never invent
        }

        // ── 6. Conservative decision ────────────────────────────────────────────────────────────────
        Candidate best = survivors.stream().max(Comparator.comparingDouble(c -> c.score)).orElseThrow();
        if (best.isModel) {
            return asExtracted(modelValue);
        }
        // If the model's own pick is implausible (failed the hard filter), overriding to a plausible
        // sibling is clearly justified → treat the margin as unbounded. Otherwise require a real,
        // conservative advantage over the model candidate before overriding.
        double margin;
        if (modelCandidate != null && survivors.contains(modelCandidate)) {
            margin = best.score - modelCandidate.score;
        } else {
            margin = Double.POSITIVE_INFINITY;
        }
        if (margin < MARGIN_THRESHOLD) {
            return asExtracted(modelValue); // near-tie → keep the model's pick
        }
        String audit = buildAuditJson(modelValue, best, significant, survivors.size(), margin,
                mu, sigma, deltas, upperCap);
        return new ResolvedReading(best.reading, SOURCE_ROLLOVER_RESOLVED, audit);
    }

    private ResolvedReading asExtracted(BigDecimal modelValue) {
        return new ResolvedReading(modelValue, SOURCE_AS_EXTRACTED, null);
    }

    /**
     * Consecutive daily deltas, positives only (drops anomalies and the negative meter-swap boundary),
     * capped to the {@link #WINDOW} most recent.
     */
    private double[] validDailyDeltas(List<DailyConfirmedReading> history) {
        if (history == null || history.size() < 2) {
            return new double[0];
        }
        // The query returns most-recent-first; sort ascending by day to diff consecutive values.
        List<DailyConfirmedReading> ordered = new ArrayList<>(history);
        ordered.sort(Comparator.comparing(DailyConfirmedReading::day));
        List<Double> deltas = new ArrayList<>();
        for (int i = 1; i < ordered.size(); i++) {
            BigDecimal prev = ordered.get(i - 1).confirmedReading();
            BigDecimal cur = ordered.get(i).confirmedReading();
            if (prev == null || cur == null) {
                continue;
            }
            double delta = cur.subtract(prev).doubleValue();
            if (delta > 0) {
                deltas.add(delta);
            }
        }
        int from = Math.max(0, deltas.size() - WINDOW);
        List<Double> recent = deltas.subList(from, deltas.size());
        double[] result = new double[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            result[i] = recent.get(i);
        }
        return result;
    }

    /**
     * Data-driven upper cap on the implied daily consumption. Prefers the fully robust Tukey upper
     * fence ({@code Q3 + k·IQR}); for too-few deltas falls back to a MAD fence floored at {@code μ + σ}.
     * Never collapses below {@code μ} (a flat scheme must still accept a normal next-day delta).
     */
    private double upperConsumptionCap(double[] deltas, double mu, double sigma, double mad) {
        double cap;
        if (deltas.length >= MIN_DELTAS_FOR_QUARTILES) {
            double[] sorted = deltas.clone();
            java.util.Arrays.sort(sorted);
            double q1 = percentile(sorted, 0.25);
            double q3 = percentile(sorted, 0.75);
            double iqr = q3 - q1;
            cap = q3 + TUKEY_K * iqr;
        } else {
            cap = mu + TUKEY_K * (2.0 * mad);
        }
        // Floor at μ + σ so a perfectly flat scheme (IQR = 0 / MAD = 0) does not reject a legitimately
        // normal next-day delta; and never below μ.
        cap = Math.max(cap, mu + sigma);
        return Math.max(cap, mu);
    }

    private double domainPenalty(double delta, double mu, double sigma) {
        double overshoot = Math.max(0.0, delta - mu) / sigma;
        return -(overshoot * overshoot);
    }

    private BigDecimal robustAnchor(BigDecimal previousConfirmed, List<DailyConfirmedReading> history) {
        BigDecimal anchor = previousConfirmed;
        if (history != null) {
            for (DailyConfirmedReading row : history) {
                BigDecimal value = row.confirmedReading();
                if (value != null && (anchor == null || value.compareTo(anchor) > 0)) {
                    anchor = value;
                }
            }
        }
        return anchor;
    }

    /**
     * Builds one candidate reading from a choice mask over the significant positions. Bit {@code i}
     * (LSB-first) chooses the selected (0) or alternate (1) digit for {@code significant.get(i)}.
     */
    private Candidate buildCandidate(String rawReading,
                                     List<RolloverPosition> significant,
                                     int mask,
                                     boolean redLastDigit) {
        char[] digits = rawReading.toCharArray();
        double confidenceLogSum = 0.0;
        boolean isModel = true;
        List<int[]> picks = new ArrayList<>(significant.size());
        for (int i = 0; i < significant.size(); i++) {
            RolloverPosition pos = significant.get(i);
            boolean useAlternate = ((mask >> i) & 1) == 1;
            int chosenDigit = useAlternate ? pos.alternateValue() : pos.selectedValue();
            double confidence = confidenceOf(useAlternate ? pos.alternateConfidence() : pos.selectedConfidence());
            confidenceLogSum += Math.log(Math.max(confidence, MIN_CONFIDENCE));
            digits[pos.position() - 1] = (char) ('0' + (chosenDigit % 10));
            if (useAlternate) {
                isModel = false;
            }
            picks.add(new int[]{pos.position(), pos.selectedValue(), chosenDigit});
        }
        BigDecimal reading = new BigDecimal(new String(digits));
        if (redLastDigit) {
            reading = reading.movePointLeft(1).setScale(1, RoundingMode.UNNECESSARY);
        }
        return new Candidate(reading, confidenceLogSum, isModel, picks);
    }

    private double confidenceOf(BigDecimal confidence) {
        if (confidence == null) {
            return DEFAULT_CONFIDENCE;
        }
        double value = confidence.doubleValue();
        if (Double.isNaN(value) || value <= 0.0) {
            return MIN_CONFIDENCE;
        }
        return Math.min(value, 1.0);
    }

    private String buildAuditJson(BigDecimal modelValue,
                                  Candidate best,
                                  List<RolloverPosition> significant,
                                  int candidateCount,
                                  double margin,
                                  double mu,
                                  double sigma,
                                  double[] deltas,
                                  double upperCap) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("originalReading", modelValue);
            root.put("chosenReading", best.reading);
            ArrayNode picks = root.putArray("picks");
            for (int[] pick : best.picks) {
                ObjectNode node = picks.addObject();
                node.put("position", pick[0]);
                node.put("modelDigit", pick[1]);
                node.put("chosenDigit", pick[2]);
            }
            root.put("candidates", candidateCount);
            root.put("margin", round(margin));
            root.put("mu", round(mu));
            root.put("sigma", round(sigma));
            if (deltas.length >= MIN_DELTAS_FOR_QUARTILES) {
                double[] sorted = deltas.clone();
                java.util.Arrays.sort(sorted);
                root.put("q1", round(percentile(sorted, 0.25)));
                root.put("q3", round(percentile(sorted, 0.75)));
            }
            root.put("upperCap", round(upperCap));
            return objectMapper.writeValueAsString(root);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("Failed to build rollover audit json: {}", ex.getMessage());
            return null;
        }
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    // ── Robust statistics helpers ───────────────────────────────────────────────────────────────────

    private static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return medianSorted(sorted);
    }

    private static double medianSorted(double[] sorted) {
        int n = sorted.length;
        if (n == 0) {
            return 0.0;
        }
        int mid = n / 2;
        return (n % 2 == 1) ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    private static double medianAbsoluteDeviation(double[] values, double centre) {
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(values[i] - centre);
        }
        return median(deviations);
    }

    /** Linear-interpolation percentile (type 7 / PERCENTILE.INC); {@code p} in [0,1], input sorted asc. */
    private static double percentile(double[] sortedAsc, double p) {
        int n = sortedAsc.length;
        if (n == 0) {
            return 0.0;
        }
        if (n == 1) {
            return sortedAsc[0];
        }
        double rank = p * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return sortedAsc[lo];
        }
        double frac = rank - lo;
        return sortedAsc[lo] + frac * (sortedAsc[hi] - sortedAsc[lo]);
    }

    /** A single enumerated rollover candidate. */
    private static final class Candidate {
        private final BigDecimal reading;
        private final double confidenceLogSum;
        private final boolean isModel;
        private final List<int[]> picks;
        private double score;

        private Candidate(BigDecimal reading, double confidenceLogSum, boolean isModel, List<int[]> picks) {
            this.reading = reading;
            this.confidenceLogSum = confidenceLogSum;
            this.isModel = isModel;
            this.picks = picks;
        }
    }

    /**
     * Result of resolution.
     *
     * @param confirmedReading the value to seed {@code confirmed_reading} with (and surface to the operator).
     * @param source           {@link #SOURCE_AS_EXTRACTED} or {@link #SOURCE_ROLLOVER_RESOLVED}.
     * @param auditJson         compact tuning blob when overridden, else {@code null}.
     */
    public record ResolvedReading(BigDecimal confirmedReading, int source, String auditJson) {
    }
}
