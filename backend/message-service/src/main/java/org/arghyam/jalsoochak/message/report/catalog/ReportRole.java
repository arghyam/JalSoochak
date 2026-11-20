package org.arghyam.jalsoochak.message.report.catalog;

import java.util.Locale;
import java.util.Optional;

/**
 * The officer roles a situation report can be configured for.
 *
 * <p>Only these two: they are the roles tenant-service's report schedulers (the subordinate lookup)
 * and analytics-service's KPI queries (the minimum supply-days bar) model.</p>
 */
public enum ReportRole {
    SECTION_OFFICER,
    SUB_DIVISIONAL_OFFICER;

    /**
     * The role a report event's {@code officerUserType} names, ignoring case and surrounding blanks;
     * empty for any other value.
     */
    public static Optional<ReportRole> fromUserType(String userType) {
        if (userType == null) {
            return Optional.empty();
        }
        String normalised = userType.trim().toUpperCase(Locale.ROOT);
        for (ReportRole role : values()) {
            if (role.name().equals(normalised)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
