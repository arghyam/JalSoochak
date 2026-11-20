package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The layout one role gets: {@code "useDefault": true} for message-service's built-in layout of the
 * (report, role) pair, or a complete {@code layout}. Exactly one of the two; never a merge.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleLayoutDTO(Boolean useDefault, ReportLayoutDTO layout) {
}
