package org.arghyam.jalsoochak.analytics.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The arithmetic every water-report figure passes through.
 *
 * <p>Two rules, applied uniformly so a reader can compare any two numbers on a report without
 * wondering how each was produced:</p>
 *
 * <ul>
 *   <li>Every ratio is rounded to one decimal place, HALF_UP.</li>
 *   <li>A zero denominator yields {@code 0}, not an error and not a blank. An officer with no
 *       schemes mapped, or whose schemes have no recorded connections, gets a report reading 0
 *       rather than no report at all — the absence is itself worth seeing.</li>
 * </ul>
 */
final class ReportMaths {

    private static final int SCALE = 1;

    private ReportMaths() {
    }

    /** {@code numerator / denominator}, to one decimal place; 0 when the denominator is 0. */
    static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return round1((double) numerator / denominator);
    }

    /** {@code 100 × part / whole}, to one decimal place; 0 when the whole is 0. */
    static double percentage(long part, long whole) {
        if (whole <= 0) {
            return 0.0;
        }
        return round1(100.0 * part / whole);
    }

    /** {@code numerator / (denominator × days)}, to one decimal place; 0 when either is 0. */
    static double perDayRatio(long numerator, long denominator, int days) {
        if (denominator <= 0 || days <= 0) {
            return 0.0;
        }
        return round1((double) numerator / ((double) denominator * days));
    }

    static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).doubleValue();
    }
}
