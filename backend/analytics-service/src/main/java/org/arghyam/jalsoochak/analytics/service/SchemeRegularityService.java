package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardBoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardLevel2BoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardLevel2MetricsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardResponse;
import org.arghyam.jalsoochak.analytics.dto.response.OutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicNationalSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.RegionWiseWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusAndTopReportingResponse;
import org.arghyam.jalsoochak.analytics.dto.response.CriticalSchemesResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ContinuousSchemesResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserNonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SubmissionStatusSummaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserSubmissionStatusResponse;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
public interface SchemeRegularityService {

    AverageSchemeRegularityResponse getAverageSchemeRegularity(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    AverageSchemeRegularityResponse getAverageSchemeRegularityForChildRegions(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    ReadingSubmissionRateResponse getReadingSubmissionRateByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    ReadingSubmissionRateResponse getReadingSubmissionRateByLgdForChildRegions(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    AverageSchemeRegularityResponse getAverageSchemeRegularityByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    AverageSchemeRegularityResponse getAverageSchemeRegularityByDepartmentForChildRegions(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    ReadingSubmissionRateResponse getReadingSubmissionRateByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    ReadingSubmissionRateResponse getReadingSubmissionRateByDepartmentForChildRegions(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    BigDecimal getAveragePerformanceScoreByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    BigDecimal getAveragePerformanceScoreByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    List<SchemeRegularityRepository.ChildRegionPerformanceScore> getChildAveragePerformanceScoreByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    List<SchemeRegularityRepository.ChildRegionPerformanceScore> getChildAveragePerformanceScoreByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegion(
            Integer tenantId, LocalDate startDate, LocalDate endDate);

    AverageWaterSupplyResponse getAverageWaterSupplyPerNation(
            LocalDate startDate, LocalDate endDate);

    // Endpoint-oriented wrappers that shape the response based on `scope` contract.
    AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionForCurrentScope(
            Integer tenantId, LocalDate startDate, LocalDate endDate);

    AverageWaterSupplyResponse getAverageWaterSupplyPerNationForChildScope(
            LocalDate startDate, LocalDate endDate);

    AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionByLgdForChildScope(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate);

    AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionByDepartmentForChildScope(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    NationalDashboardResponse getNationalDashboard(
            LocalDate startDate, LocalDate endDate);

    NationalDashboardResponse refreshNationalDashboard(
            LocalDate startDate, LocalDate endDate);

    // Endpoint-oriented wrapper (keeps controller thin; allows future shaping/versioning).
    NationalDashboardResponse getNationalDashboardForApi(
            LocalDate startDate, LocalDate endDate);

    NationalDashboardBoundaryResponse getNationalDashboardBoundariesForApi();

    NationalDashboardLevel2BoundaryResponse getNationalDashboardLevel2BoundariesForApi();

    NationalDashboardLevel2MetricsResponse getNationalDashboardLevel2MetricsForApi(
            LocalDate startDate, LocalDate endDate);

    AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate);

    AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    RegionWiseWaterQuantityResponse getRegionWiseWaterQuantityByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    RegionWiseWaterQuantityResponse getRegionWiseWaterQuantityByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    PeriodicWaterQuantityResponse getPeriodicWaterQuantityByLgdId(
            Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale);

    PeriodicWaterQuantityResponse getPeriodicWaterQuantityByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale);

    PeriodicSchemeRegularityResponse getPeriodicSchemeRegularityByLgdId(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale);

    PeriodicSchemeRegularityResponse getPeriodicSchemeRegularityByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale);

    PeriodicSchemeRegularityResponse getPeriodicSchemeRegularityForNation(
            LocalDate startDate, LocalDate endDate, PeriodScale scale);

    PeriodicNationalSchemeRegularityResponse getPeriodicSchemeRegularityForNationForApi(
            LocalDate startDate, LocalDate endDate, PeriodScale scale);

    PeriodicOutageReasonSchemeCountResponse getPeriodicOutageReasonSchemeCountByLgdId(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale);

    PeriodicOutageReasonSchemeCountResponse getPeriodicOutageReasonSchemeCountByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale);

    OutageReasonSchemeCountResponse getOutageReasonSchemeCountByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    OutageReasonSchemeCountResponse getOutageReasonSchemeCountByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    UserOutageReasonSchemeCountResponse getOutageReasonSchemeCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate);

    UserOutageReasonSchemeCountResponse getOutageReasonSchemeCountByUserUuid(
            Integer tenantId, UUID userUuid, LocalDate startDate, LocalDate endDate);

    NonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate);

    NonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate);

    UserNonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate);

    UserNonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByUserUuid(
            Integer tenantId, UUID userUuid, LocalDate startDate, LocalDate endDate);

    UserSubmissionStatusResponse getSubmissionStatusByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate);

    UserSubmissionStatusResponse getSubmissionStatusByUserUuid(
            Integer tenantId, UUID userUuid, LocalDate startDate, LocalDate endDate);

    SubmissionStatusSummaryResponse getSubmissionStatusSummaryByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate);

    SubmissionStatusSummaryResponse getSubmissionStatusSummaryByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate);

    Map<String, Integer> getSchemeStatusCountByLgd(Integer tenantId, Integer lgdId);

    Map<String, Integer> getSchemeStatusCountByDepartment(Integer tenantId, Integer departmentId);

    SchemeStatusAndTopReportingResponse getSchemeStatusAndTopReportingByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate, Integer pageNumber, Integer limit);

    SchemeStatusAndTopReportingResponse getSchemeStatusAndTopReportingByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate, Integer pageNumber, Integer limit);

    SchemeRegularityListResponse getSchemeRegionReportByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate, Integer pageNumber, Integer count);

    SchemeRegularityListResponse getSchemeRegionReportByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate, Integer pageNumber, Integer count);

    CriticalSchemesResponse getCriticalSchemesByLgd(
            Integer tenantId, Integer lgdId, boolean list, Integer page, Integer limit);

    CriticalSchemesResponse getCriticalSchemesByDepartment(
            Integer tenantId, Integer departmentId, boolean list, Integer page, Integer limit);

    ContinuousSchemesResponse getContinuousSchemesByLgd(
            Integer tenantId,
            Integer lgdId,
            LocalDate startDate,
            LocalDate endDate,
            boolean list,
            Integer page,
            Integer limit);

    ContinuousSchemesResponse getContinuousSchemesByDepartment(
            Integer tenantId,
            Integer departmentId,
            LocalDate startDate,
            LocalDate endDate,
            boolean list,
            Integer page,
            Integer limit);
}
