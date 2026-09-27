package org.arghyam.jalsoochak.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Filter parameters for {@code POST /api/v1/tenant/user/staff/reports}.
 * Mirrors the query filters of {@code GET /api/v1/tenant/user/staff} so the
 * exported file matches what the State Admin sees in the UI.
 *
 * <p>All fields are optional — an empty body exports the full filtered set.
 */
@Schema(description = "Filters that scope the staff export (same as listStaff)")
public record StaffReportRequestDTO(
        @Schema(description = "Roles to include (case-insensitive)", example = "[\"SUB_DIVISIONAL_OFFICER\"]")
        List<String> roles,

        @Schema(description = "Status filter — ACTIVE, INACTIVE, or numeric code", example = "ACTIVE")
        String status,

        @Schema(description = "Exact-match staff name", example = "Anita Sharma")
        String name
) {
}
