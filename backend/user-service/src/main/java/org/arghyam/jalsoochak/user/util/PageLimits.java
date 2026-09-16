package org.arghyam.jalsoochak.user.util;

import lombok.experimental.UtilityClass;

/**
 * Server-side bounds for paginated reads.
 *
 * <p>Without these, {@code size=99999} turns a page into a bulk export and {@code page * size}
 * overflows {@code int} into a negative OFFSET. Callers must clamp rather than validate-and-reject
 * so that an out-of-range page size degrades to the maximum page instead of failing a dashboard.
 */
@UtilityClass
public class PageLimits {

    /** Hard server-side ceiling on rows per page, regardless of what the caller asks for. */
    public static final int MAX_PAGE_SIZE = 100;

    public static int clampPage(int page) {
        return Math.max(0, page);
    }

    public static int clampSize(int size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * Computes the SQL OFFSET for an already-clamped page and size. The multiplication is done in
     * {@code long} and saturated, so a large page number yields an empty page rather than
     * overflowing {@code int} into a negative — and therefore invalid — OFFSET.
     */
    public static int offset(int clampedPage, int clampedSize) {
        long offset = (long) clampPage(clampedPage) * clampSize(clampedSize);
        return (int) Math.min(offset, Integer.MAX_VALUE);
    }
}
