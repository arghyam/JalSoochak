package org.arghyam.jalsoochak.user.dto.response;

import lombok.Builder;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One reading submitted against a scheme, with the submitter's reading-compliance figures.
 *
 * <p>Rows are not restricted to pump operators: any user who submits a reading for the scheme
 * appears here, with {@code submittedByRole} carrying the raw
 * {@code common_schema.user_type_master_table.c_name} (e.g. {@code SECTION_OFFICER}) alongside
 * {@code submittedByRoleLabel}, its short form (e.g. {@code SO}), so a caller can suffix the name
 * without owning an abbreviation table of its own. The compliance figures that measure a submitter against an expected
 * reporting window — {@code onboardingDate}, {@code totalActiveDays}, {@code missedSubmissionDays},
 * {@code inactiveDays}, {@code missingSubmissionCount} and {@code reportingRatePercent} — are
 * populated for pump operators only and are {@code null} for every other role, because only a pump
 * operator has a per-scheme reporting obligation to measure against. {@code submittedDays} and
 * {@code lastSubmissionAt} are plain counts over the requested range and are populated for all roles.
 */
@Builder
public record SchemeReadingComplianceRowDTO(
        Long id,
        String uuid,
        String name,
        String submittedByRole,
        String submittedByRoleLabel,
        String email,
        String phoneNumber,
        TenantUserStatus status,
        Long schemeId,
        String schemeName,
        Integer schemeMappingStatus,
        LocalDate onboardingDate,
        Integer totalActiveDays,
        Integer submittedDays,
        Integer missedSubmissionDays,
        Integer inactiveDays,
        Integer missingSubmissionCount,
        BigDecimal reportingRatePercent,
        LocalDate readingDate,
        LocalDateTime readingAt,
        LocalDateTime lastSubmissionAt,
        BigDecimal confirmedReading
) {
}
