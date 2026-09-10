package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One bucket of a scheme status breakdown, along either the work-status or the operating-status
 * dimension. The label always comes from
 * {@link org.arghyam.jalsoochak.analytics.enums.SchemeWorkStatus} or
 * {@link org.arghyam.jalsoochak.analytics.enums.SchemeOperatingStatus} so it cannot drift from the code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemeStatusCountDTO {

    /** Stored status code, or {@code null} for schemes with no status recorded. */
    private Integer code;

    /** Canonical label for {@code code}, or {@code "Unknown"}. */
    private String label;

    private Integer count;
}
