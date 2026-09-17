package org.arghyam.jalsoochak.scheme.dto;

import lombok.Builder;

/**
 * One bucket of a scheme status breakdown, along either the work-status or the operating-status
 * dimension.
 *
 * <p>The label always comes from {@link org.arghyam.jalsoochak.scheme.enums.SchemeWorkStatus} or
 * {@link org.arghyam.jalsoochak.scheme.enums.SchemeOperatingStatus} so it cannot drift from the code.
 *
 * <p>This is deliberately wire-identical to analytics-service's {@code SchemeStatusCountDTO}: both
 * services report the same breakdown, so a client can render either with one component.
 */
@Builder
public record SchemeStatusCountDTO(
        /** Stored status code, or {@code null} for schemes with no status recorded. */
        Integer code,

        /** Canonical label for {@code code}, or {@code "Unknown"}. */
        String label,

        long count
) {
}
