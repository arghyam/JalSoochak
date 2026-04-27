package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.response.TenantDetailsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.TenantPerformanceScoreResponse;

import java.time.LocalDate;

public interface TenantDetailsService {

    TenantDetailsResponse getTenantDetails(Integer tenantId, Integer parentLgdId);

    TenantDetailsResponse getTenantDetailsByParentDepartment(Integer tenantId, Integer parentDepartmentId);

    TenantDetailsResponse getTenantDetailsWithAggregatedMetrics(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    TenantDetailsResponse getTenantDetailsByParentDepartmentWithAggregatedMetrics(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    TenantPerformanceScoreResponse getTenantPerformanceScoreByParentLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    TenantPerformanceScoreResponse getTenantPerformanceScoreByParentDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);
}
