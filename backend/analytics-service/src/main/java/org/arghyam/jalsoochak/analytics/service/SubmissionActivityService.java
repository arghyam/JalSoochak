package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.response.HourlySubmissionActivityResponse;

import java.time.LocalDate;

/**
 * Serves the hourly reading-submission activity KPIs (a new dashboard widget). Computed
 * on the fly from the base meter-reading fact so any region level is supported without
 * pre-rolled per-region rows.
 */
public interface SubmissionActivityService {

    /**
     * Hourly reading-submission activity for a tenant, optionally scoped to a single LGD
     * or department region (provide at most one; neither = whole tenant / state view).
     */
    HourlySubmissionActivityResponse getHourlySubmissionActivity(
            Integer tenantId, Integer lgdId, Integer departmentId, LocalDate startDate, LocalDate endDate);
}
