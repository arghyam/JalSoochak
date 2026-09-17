package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single scheme's status along one dimension.
 *
 * <p>Replaces the {@code statusCode}/{@code status} pair, which reported {@code operating_status}
 * collapsed to {@code "active"}/{@code "inactive"}. The label always comes from
 * {@link org.arghyam.jalsoochak.analytics.enums.SchemeWorkStatus} or
 * {@link org.arghyam.jalsoochak.analytics.enums.SchemeOperatingStatus} so it cannot drift from the code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemeStatusDTO {

    /** Stored status code, or {@code null} when the scheme has no status recorded. */
    private Integer code;

    /** Canonical label for {@code code}, or {@code "Unknown"}. */
    private String label;
}
