package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardBoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.OutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.dto.response.RegionWiseWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusAndTopReportingResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserNonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SubmissionStatusSummaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserSubmissionStatusResponse;
import org.arghyam.jalsoochak.analytics.entity.DimUser;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeRegularityServiceImplTest {

    @Mock
    private SchemeRegularityRepository schemeRegularityRepository;
    @Mock
    private DimTenantRepository dimTenantRepository;
    @Mock
    private DimUserRepository dimUserRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private SchemeRegularityServiceImpl service;

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 3);
    private static final UUID USER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void getAverageSchemeRegularity_invalidLgd_throwsBadRequest() {
        assertThatThrownBy(() -> service.getAverageSchemeRegularity(1, 0, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lgd_id must be a positive integer");
    }

    @Test
    void getAverageSchemeRegularity_invalidDateRange_throwsBadRequest() {
        assertThatThrownBy(() -> service.getAverageSchemeRegularity(1, 101, END, START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end_date must be on or after start_date");
    }

    @Test
    void getAverageSchemeRegularity_cacheHit_returnsCachedAndSkipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":scheme_regularity:tenant:1:lgd:101:start:2026-01-01:end:2026-01-03:v2";
        AverageSchemeRegularityResponse cached = AverageSchemeRegularityResponse.builder()
                .lgdId(101)
                .averageRegularity(new BigDecimal("0.7777"))
                .build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", AverageSchemeRegularityResponse.class)).thenReturn(cached);

        AverageSchemeRegularityResponse response = service.getAverageSchemeRegularity(1, 101, START, END);

        assertThat(response.getAverageRegularity()).isEqualByComparingTo("0.7777");
        verify(schemeRegularityRepository, never()).getSchemeRegularityMetrics(any(), any(), any(), any());
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void getAverageSchemeRegularity_cacheMiss_computesAndWritesCache() throws Exception {
        mockRedisValueOps();
        String key = ":scheme_regularity:tenant:1:lgd:101:start:2026-01-01:end:2026-01-03:v2";
        when(valueOperations.get(key)).thenReturn(null);
        // KPI is now regularSchemeCount / schemeCount: 1 regular of 2 schemes = 0.5000.
        when(schemeRegularityRepository.getSchemeRegularityMetrics(1, 101, START, END))
                .thenReturn(new SchemeRegularityRepository.SchemeRegularityMetrics(2, 3, 1));
        when(schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(1))
                .thenReturn(new BigDecimal("90"));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageSchemeRegularityResponse response = service.getAverageSchemeRegularity(1, 101, START, END);

        assertThat(response.getDaysInRange()).isEqualTo(3);
        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getTotalSupplyDays()).isEqualTo(3);
        assertThat(response.getRegularSchemeCount()).isEqualTo(1);
        assertThat(response.getAverageRegularity()).isEqualByComparingTo("0.5000");
        // 90% of a 3-day window rounds (half-up) to 3 days required.
        assertThat(response.getThresholdPercent()).isEqualByComparingTo("90");
        assertThat(response.getThresholdDays()).isEqualTo(3);
        verify(valueOperations, times(1)).set(eq(key), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getAverageSchemeRegularity_cacheReadFailure_fallsBackToRepository() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenThrow(new RuntimeException("redis read failed"));
        // 1 regular of 3 schemes = 0.3333.
        when(schemeRegularityRepository.getSchemeRegularityMetrics(1, 101, START, END))
                .thenReturn(new SchemeRegularityRepository.SchemeRegularityMetrics(3, 1, 1));
        when(schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(1))
                .thenReturn(new BigDecimal("90"));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageSchemeRegularityResponse response = service.getAverageSchemeRegularity(1, 101, START, END);

        assertThat(response.getAverageRegularity()).isEqualByComparingTo("0.3333");
        verify(schemeRegularityRepository, times(1)).getSchemeRegularityMetrics(1, 101, START, END);
    }

    @Test
    void getChildAveragePerformanceScoreByLgd_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":performance_score:child:lgd:101:start:2026-01-01:end:2026-01-03:v1";
        var cached = List.of(
                new SchemeRegularityRepository.ChildRegionPerformanceScore(401, null, new BigDecimal("0.9")));
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue(eq("cached"), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(cached);

        var response = service.getChildAveragePerformanceScoreByLgd(101, START, END);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().lgdId()).isEqualTo(401);
        verify(schemeRegularityRepository, never()).getChildAveragePerformanceScoreByLgd(any(), any(), any());
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void getChildAveragePerformanceScoreByDepartment_cacheMiss_writesCache() throws Exception {
        mockRedisValueOps();
        String key = ":performance_score:child:department:201:start:2026-01-01:end:2026-01-03:v1";
        when(valueOperations.get(key)).thenReturn(null);
        var repoRows = List.of(
                new SchemeRegularityRepository.ChildRegionPerformanceScore(null, 501, new BigDecimal("0.88")));
        when(schemeRegularityRepository.getChildAveragePerformanceScoreByDepartment(201, START, END))
                .thenReturn(repoRows);
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        var response = service.getChildAveragePerformanceScoreByDepartment(201, START, END);

        assertThat(response).isEqualTo(repoRows);
        verify(valueOperations, times(1)).set(eq(key), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getAverageSchemeRegularityForChildRegions_whenLevelHasNoChildren_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(6);

        assertThatThrownBy(() -> service.getAverageSchemeRegularityForChildRegions(1, 101, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No child LGD level available");
    }

    @Test
    void getReadingSubmissionRateByDepartmentForChildRegions_aggregatesChildrenCorrectly() throws Exception {
        mockRedisValueOps();
        String key = ":reading_submission_rate:tenant:1:department:201:scope:child:start:2026-01-01:end:2026-01-03:v3";
        when(valueOperations.get(key)).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevelForTenant(1, 201)).thenReturn(2);
        when(schemeRegularityRepository.getChildReadingSubmissionRateMetricsByDepartment(1, 201, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics(
                                null, 301, "Block A", 2, 6, new BigDecimal("1.0000")),
                        new SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics(
                                null, 302, "Block B", 1, 2, new BigDecimal("0.6667"))
                ));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        ReadingSubmissionRateResponse response =
                service.getReadingSubmissionRateByDepartmentForChildRegions(1, 201, START, END);

        assertThat(response.getDaysInRange()).isEqualTo(3);
        assertThat(response.getSchemeCount()).isEqualTo(3);
        assertThat(response.getTotalSubmissionDays()).isEqualTo(8);
        assertThat(response.getReadingSubmissionRate()).isEqualByComparingTo("0.8889");
        assertThat(response.getChildRegionCount()).isEqualTo(2);
        assertThat(response.getChildRegions()).hasSize(2);
        verify(valueOperations, times(1)).set(eq(key), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getPeriodicWaterQuantityByLgdId_capsPeriodEndDateAtRequestedEndDate() {
        LocalDate requestedEnd = LocalDate.of(2026, 1, 10);
        when(schemeRegularityRepository.getPeriodicWaterQuantityByLgdId(101, START, requestedEnd, PeriodScale.WEEK))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.PeriodicWaterQuantityMetrics(
                                LocalDate.of(2026, 1, 6),
                                LocalDate.of(2026, 1, 12),
                                "2026-W02",
                                new BigDecimal("50.1250"),
                                77L,
                                70L,
                                80L)
                ));

        PeriodicWaterQuantityResponse response =
                service.getPeriodicWaterQuantityByLgdId(101, START, requestedEnd, PeriodScale.WEEK);

        assertThat(response.getScale()).isEqualTo("week");
        assertThat(response.getPeriodCount()).isEqualTo(1);
        assertThat(response.getMetrics().getFirst().getPeriodEndDate()).isEqualTo(requestedEnd);
        assertThat(response.getMetrics().getFirst().getAverageWaterQuantity()).isEqualByComparingTo("50.1250");
    }

    @Test
    void getPeriodicSchemeRegularityByLgdId_capsAndComputesAverageRegularity() {
        LocalDate requestedEnd = LocalDate.of(2026, 1, 10);
        when(schemeRegularityRepository.getPeriodicSchemeRegularityByLgdId(
                        1,
                        101,
                        START,
                        requestedEnd,
                        PeriodScale.WEEK))
                .thenReturn(
                        List.of(
                                new SchemeRegularityRepository.PeriodicSchemeRegularityMetrics(
                                        LocalDate.of(2025, 12, 29),
                                        LocalDate.of(2026, 1, 12),
                                        2,
                                        0L,
                                        2,
                                        1,
                                        15L)));

        PeriodicSchemeRegularityResponse response =
                service.getPeriodicSchemeRegularityByLgdId(1, 101, START, requestedEnd, PeriodScale.WEEK);

        assertThat(response.getScale()).isEqualTo("week");
        assertThat(response.getPeriodCount()).isEqualTo(1);
        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getMetrics().getFirst().getPeriodStartDate()).isEqualTo(START);
        assertThat(response.getMetrics().getFirst().getPeriodEndDate()).isEqualTo(requestedEnd);
        assertThat(response.getMetrics().getFirst().getTotalSupplyDays()).isEqualTo(2);
        assertThat(response.getMetrics().getFirst().getTotalWaterQuantity()).isEqualTo(15L);
        // KPI is now regularSchemeCount / schemeCount (per-bucket count classified in SQL): 1 / 2 = 0.5000.
        assertThat(response.getMetrics().getFirst().getRegularSchemeCount()).isEqualTo(1);
        assertThat(response.getMetrics().getFirst().getAverageRegularity()).isEqualByComparingTo("0.5000");
    }

    @Test
    void getPeriodicSchemeRegularityForNation_capsAndComputesAverageRegularity() throws Exception {
        LocalDate requestedEnd = LocalDate.of(2026, 1, 10);

        mockRedisValueOps();
        String cacheKey = ":scheme_regularity:nation:periodic-scheme-regularity"
                + ":scale:week:start:2026-01-01:end:2026-01-10:v2";
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        when(schemeRegularityRepository.getPeriodicSchemeRegularityForNation(
                        START, requestedEnd, PeriodScale.WEEK))
                .thenReturn(
                        List.of(
                                new SchemeRegularityRepository.PeriodicSchemeRegularityMetrics(
                                        LocalDate.of(2025, 12, 29),
                                        LocalDate.of(2026, 1, 12),
                                        2,
                                        0L,
                                        2,
                                        1,
                                        115L)));

        PeriodicSchemeRegularityResponse response =
                service.getPeriodicSchemeRegularityForNation(START, requestedEnd, PeriodScale.WEEK);

        assertThat(response.getScale()).isEqualTo("week");
        assertThat(response.getPeriodCount()).isEqualTo(1);
        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getMetrics().getFirst().getPeriodStartDate()).isEqualTo(START);
        assertThat(response.getMetrics().getFirst().getPeriodEndDate()).isEqualTo(requestedEnd);
        assertThat(response.getMetrics().getFirst().getTotalSupplyDays()).isEqualTo(2);
        assertThat(response.getMetrics().getFirst().getTotalWaterQuantity()).isEqualTo(115L);
        // KPI is now regularSchemeCount / schemeCount (per-bucket count classified in SQL): 1 / 2 = 0.5000.
        assertThat(response.getMetrics().getFirst().getRegularSchemeCount()).isEqualTo(1);
        assertThat(response.getMetrics().getFirst().getAverageRegularity()).isEqualByComparingTo("0.5000");

        verify(schemeRegularityRepository, times(1))
                .getPeriodicSchemeRegularityForNation(START, requestedEnd, PeriodScale.WEEK);

        verify(valueOperations, times(1)).set(eq(cacheKey), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getPeriodicSchemeRegularityForNationForApi_doesNotReturnLgdOrDepartmentFields() throws Exception {
        LocalDate requestedEnd = LocalDate.of(2026, 1, 10);

        mockRedisValueOps();
        String cacheKey = ":scheme_regularity:nation:periodic-scheme-regularity:api"
                + ":scale:week:start:2026-01-01:end:2026-01-10:v2";
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        when(schemeRegularityRepository.getPeriodicSchemeRegularityForNation(
                        START, requestedEnd, PeriodScale.WEEK))
                .thenReturn(
                        List.of(
                                new SchemeRegularityRepository.PeriodicSchemeRegularityMetrics(
                                        LocalDate.of(2025, 12, 29),
                                        LocalDate.of(2026, 1, 12),
                                        2,
                                        123L,
                                        2,
                                        1,
                                        115L)));

        var response =
                service.getPeriodicSchemeRegularityForNationForApi(START, requestedEnd, PeriodScale.WEEK);

        assertThat(response.getScale()).isEqualTo("week");
        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getTotalAchievedFhtcCount()).isEqualTo(123L);
        assertThat(response.getMetrics().getFirst().getTotalWaterQuantity()).isEqualTo(115L);
        verify(valueOperations, times(1)).set(eq(cacheKey), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getPeriodicOutageReasonSchemeCountByLgdId_capsPeriodBoundsAndAggregatesReasons() {
        LocalDate requestedEnd = LocalDate.of(2026, 1, 10);
        when(schemeRegularityRepository.getPeriodicOutageReasonSchemeCountByLgdId(
                        1, 101, START, requestedEnd, PeriodScale.WEEK))
                .thenReturn(
                        List.of(
                                new SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow(
                                        LocalDate.of(2025, 12, 29),
                                        LocalDate.of(2026, 1, 4),
                                        "draught",
                                        1),
                                new SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow(
                                        LocalDate.of(2025, 12, 29),
                                        LocalDate.of(2026, 1, 4),
                                        "no_electricity",
                                        2)));

        PeriodicOutageReasonSchemeCountResponse response =
                service.getPeriodicOutageReasonSchemeCountByLgdId(1, 101, START, requestedEnd, PeriodScale.WEEK);

        assertThat(response.getScale()).isEqualTo("week");
        assertThat(response.getPeriodCount()).isEqualTo(1);
        assertThat(response.getMetrics().getFirst().getPeriodStartDate()).isEqualTo(START);
        assertThat(response.getMetrics().getFirst().getPeriodEndDate()).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(response.getMetrics().getFirst().getOutageReasonSchemeCount())
                .containsExactlyInAnyOrderEntriesOf(Map.of("draught", 1, "no_electricity", 2));
    }

    @Test
    void getPeriodicOutageReasonSchemeCountByDepartment_cacheKeyIncludesTenantId_andWritesCache() throws Exception {
        mockRedisValueOps();
        LocalDate requestedEnd = LocalDate.of(2026, 1, 10);
        String cacheKey = ":outage_reasons:periodic:tenant:1:department:201:scale:week:start:2026-01-01:end:2026-01-10:v1";
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        when(schemeRegularityRepository.getPeriodicOutageReasonSchemeCountByDepartment(
                        1, 201, START, requestedEnd, PeriodScale.WEEK))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow(
                                LocalDate.of(2025, 12, 29),
                                LocalDate.of(2026, 1, 4),
                                "draught",
                                1)));

        PeriodicOutageReasonSchemeCountResponse response =
                service.getPeriodicOutageReasonSchemeCountByDepartment(1, 201, START, requestedEnd, PeriodScale.WEEK);

        assertThat(response.getScale()).isEqualTo("week");
        assertThat(response.getDepartmentId()).isEqualTo(201);
        verify(valueOperations, times(1)).set(eq(cacheKey), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getOutageReasonSchemeCountByLgd_usesTableReasonValues() {
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(3);
        when(schemeRegularityRepository.getOutageReasonSchemeCountByLgd(1, 101, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.OutageReasonSchemeCount("no_electricity", 4)));
        when(schemeRegularityRepository.getChildRegionsByLgd(1, 101))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionRef(401, null, "Village A"),
                        new SchemeRegularityRepository.ChildRegionRef(402, null, "Village B")
                ));
        when(schemeRegularityRepository.getChildOutageReasonSchemeCountByLgd(1, 101, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount(401, null, "draught", 2),
                        new SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount(999, null, "motor_burnt", 5)
                ));

        OutageReasonSchemeCountResponse response =
                service.getOutageReasonSchemeCountByLgd(1, 101, START, END);

        assertThat(response.getOutageReasonSchemeCount())
                .containsExactlyEntriesOf(Map.of("no_electricity", 4));
        assertThat(response.getChildRegions()).hasSize(2);
        assertThat(response.getChildRegions().get(0).getOutageReasonSchemeCount())
                .containsExactlyEntriesOf(Map.of("draught", 2));
        assertThat(response.getChildRegions().get(1).getOutageReasonSchemeCount())
                .isEmpty();
    }

    @Test
    void getOutageReasonSchemeCountByLgd_cacheKeyIncludesTenantId_avoidsTenantCollision() throws Exception {
        mockRedisValueOps();
        LocalDate start = LocalDate.of(2026, 3, 12);
        LocalDate end = LocalDate.of(2026, 4, 10);
        int parentLgdId = 1;

        String keyTenant17 = ":outage_reasons:tenant:17:parent_lgd:1:start:2026-03-12:end:2026-04-10:v2";
        String keyTenant79 = ":outage_reasons:tenant:79:parent_lgd:1:start:2026-03-12:end:2026-04-10:v2";

        OutageReasonSchemeCountResponse cachedTenant17 = OutageReasonSchemeCountResponse.builder()
                .lgdId(parentLgdId)
                .startDate(start)
                .endDate(end)
                .outageReasonSchemeCount(Map.of("no_water_supply", 1))
                .childRegionCount(0)
                .childRegions(List.of())
                .build();

        when(valueOperations.get(keyTenant17)).thenReturn("cached-17");
        when(objectMapper.readValue("cached-17", OutageReasonSchemeCountResponse.class)).thenReturn(cachedTenant17);
        when(valueOperations.get(keyTenant79)).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevelForTenant(79, parentLgdId)).thenReturn(1);
        when(schemeRegularityRepository.getOutageReasonSchemeCountByLgd(79, parentLgdId, start, end)).thenReturn(List.of());
        when(schemeRegularityRepository.getChildRegionsByLgd(79, parentLgdId)).thenReturn(List.of());
        when(schemeRegularityRepository.getChildOutageReasonSchemeCountByLgd(79, parentLgdId, start, end)).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        OutageReasonSchemeCountResponse fromCache = service.getOutageReasonSchemeCountByLgd(17, parentLgdId, start, end);
        assertThat(fromCache.getOutageReasonSchemeCount()).containsEntry("no_water_supply", 1);
        verify(schemeRegularityRepository, never()).getLgdLevelForTenant(17, parentLgdId);

        service.getOutageReasonSchemeCountByLgd(79, parentLgdId, start, end);
        verify(valueOperations, times(1)).get(keyTenant17);
        verify(valueOperations, times(1)).get(keyTenant79);
    }

    @Test
    void getSchemeStatusCountByLgd_handlesNullCountsAsZero() {
        when(schemeRegularityRepository.getSchemeStatusCountByLgd(1, 101))
                .thenReturn(new SchemeRegularityRepository.SchemeStatusCount(null, 7));

        Map<String, Integer> result = service.getSchemeStatusCountByLgd(1, 101);

        assertThat(result)
                .containsEntry("active_schemes_count", 0)
                .containsEntry("inactive_schemes_count", 7);
    }

    @Test
    void getSchemeStatusAndTopReportingByLgd_mapsParentLevelImmediateParentLevelAndLadders() throws Exception {
        mockRedisValueOps();
        String key = ":schemes:dashboard:tenant:12:parent_lgd:101:page:1:limit:5:start:2026-01-01:end:2026-01-03:sort_by:reportingRate:sort_dir:desc:v4";
        when(valueOperations.get(key)).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        when(schemeRegularityRepository.getLgdLevelForTenant(12, 101)).thenReturn(2);
        when(schemeRegularityRepository.getSchemeStatusCountByLgd(12, 101))
                .thenReturn(new SchemeRegularityRepository.SchemeStatusCount(1, 1));
        when(schemeRegularityRepository.getSchemeCountByLgdInScope(12, 101)).thenReturn(2L);
        when(schemeRegularityRepository.getParentLgdCNameByLgd(12, 101)).thenReturn("Parent");
        when(schemeRegularityRepository.getParentLgdTitleByLgd(12, 101)).thenReturn("District");
        when(schemeRegularityRepository.getTopSchemeSubmissionMetricsByLgd(12, 101, START, END, 5, 0, "reportingRate", "desc"))
                .thenReturn(List.of(new SchemeRegularityRepository.SchemeSubmissionMetrics(
                        1,
                        "Scheme A",
                        1,
                        2,
                        150L,
                        100,
                        "Immediate Parent",
                        "Block",
                        3,
                        null,
                        null,
                        null,
                        null,
                        10, 50, 100, 101, null, null,
                        2001, 2002, null, null, null, null,
                        List.of(100, 101),
                        List.of("Immediate Parent", "Other Parent"),
                        List.of("Block", "Block"),
                        List.of(3, 3)
                )));

        SchemeStatusAndTopReportingResponse response =
                service.getSchemeStatusAndTopReportingByLgd(12, 101, START, END, 1, 5, "reportingRate", "desc");

        assertThat(response.getParentLgdLevel()).isEqualTo(2);
        assertThat(response.getParentDepartmentLevel()).isNull();
        assertThat(response.getTotalCount()).isEqualTo(2L);
        assertThat(response.getTopSchemes()).hasSize(1);
        assertThat(response.getTopSchemes().getFirst().getImmediateParentLgdLevel()).isEqualTo(3);
        assertThat(response.getTopSchemes().getFirst().getSuppliedLgdLocations())
                .extracting(SchemeStatusAndTopReportingResponse.SuppliedLgdLocation::getLgdId)
                .containsExactly(100, 101);
        assertThat(response.getTopSchemes().getFirst().getLgdLadder())
                .containsEntry("level_1", 10)
                .containsEntry("level_4", 101)
                .containsEntry("level_6", null);
        assertThat(response.getTopSchemes().getFirst().getDepartmentLadder())
                .containsEntry("level_1", 2001)
                .containsEntry("level_2", 2002)
                .containsEntry("level_6", null);
    }

    @Test
    void getSchemeStatusAndTopReportingByDepartment_mapsParentLevelImmediateParentLevelAndLadders() throws Exception {
        when(schemeRegularityRepository.getDepartmentLevelForTenant(12, 201)).thenReturn(4);
        when(schemeRegularityRepository.getSchemeStatusCountByDepartment(12, 201))
                .thenReturn(new SchemeRegularityRepository.SchemeStatusCount(2, 0));
        when(schemeRegularityRepository.getSchemeCountByDepartmentInScope(12, 201)).thenReturn(5L);
        when(schemeRegularityRepository.getParentDepartmentCNameByDepartment(12, 201)).thenReturn("Dept");
        when(schemeRegularityRepository.getParentDepartmentTitleByDepartment(12, 201)).thenReturn("Division");
        when(schemeRegularityRepository.getTopSchemeSubmissionMetricsByDepartment(12, 201, START, END, 3, 0, "reportingRate", "desc"))
                .thenReturn(List.of(new SchemeRegularityRepository.SchemeSubmissionMetrics(
                        2,
                        "Scheme B",
                        1,
                        3,
                        80L,
                        null,
                        null,
                        null,
                        null,
                        200,
                        "Immediate Dept",
                        "SubDivision",
                        5,
                        11, 22, 33, null, null, null,
                        900, 901, 902, 903, null, null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )));

        SchemeStatusAndTopReportingResponse response =
                service.getSchemeStatusAndTopReportingByDepartment(12, 201, START, END, 1, 3, "reportingRate", "desc");

        assertThat(response.getParentLgdLevel()).isNull();
        assertThat(response.getParentDepartmentLevel()).isEqualTo(4);
        assertThat(response.getTotalCount()).isEqualTo(5L);
        assertThat(response.getTopSchemes()).hasSize(1);
        assertThat(response.getTopSchemes().getFirst().getImmediateParentDepartmentLevel()).isEqualTo(5);
        assertThat(response.getTopSchemes().getFirst().getLgdLadder())
                .containsEntry("level_1", 11)
                .containsEntry("level_3", 33);
        assertThat(response.getTopSchemes().getFirst().getDepartmentLadder())
                .containsEntry("level_1", 900)
                .containsEntry("level_4", 903);
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegion_whenTenantMissing_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegion(10, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.SchemeWaterSupplyMetrics(
                        1, "Scheme X", 100L, 90L, 110L, 1000L, 2, new BigDecimal("5.0000")
                )));
        when(dimTenantRepository.findById(10)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAverageWaterSupplyPerCurrentRegion(10, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tenant not found for tenant_id: 10");
    }

    @Test
    void getAverageWaterSupplyPerNation_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":water_supply:nation:start:2026-01-01:end:2026-01-03:v4";
        AverageWaterSupplyResponse cached = AverageWaterSupplyResponse.builder()
                .childRegionCount(1)
                .build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", AverageWaterSupplyResponse.class)).thenReturn(cached);

        AverageWaterSupplyResponse response = service.getAverageWaterSupplyPerNation(START, END);

        assertThat(response.getChildRegionCount()).isEqualTo(1);
        verify(schemeRegularityRepository, never()).getAverageWaterSupplyPerNation(any(), any());
    }

    @Test
    void getRegionWiseWaterQuantityByLgd_cacheKeyIncludesTenantId_andWritesCache() throws Exception {
        mockRedisValueOps();
        String cacheKey = ":water_quantity:region_wise:tenant:1:parent_lgd:101:start:2026-01-01:end:2026-01-03:v6";
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        when(schemeRegularityRepository.getLgdLevel(101)).thenReturn(2);
        when(schemeRegularityRepository.getRegionWiseWaterQuantityByLgd(1, 101, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterQuantityMetrics(401, null, "LGD-A", 120L, 10L, 9L, 12L, 5L)
                ));

        RegionWiseWaterQuantityResponse response = service.getRegionWiseWaterQuantityByLgd(1, 101, START, END);

        assertThat(response.getParentLgdId()).isEqualTo(101);
        verify(valueOperations, times(1)).set(eq(cacheKey), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getRegionWiseWaterQuantityByDepartment_cacheKeyIncludesTenantId_andWritesCache() throws Exception {
        mockRedisValueOps();
        String cacheKey = ":water_quantity:region_wise:tenant:1:parent_department:201:start:2026-01-01:end:2026-01-03:v4";
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        when(schemeRegularityRepository.getDepartmentLevel(201)).thenReturn(2);
        when(schemeRegularityRepository.getRegionWiseWaterQuantityByDepartment(1, 201, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterQuantityMetrics(null, 501, "Dept-A", 150L, 11L, 10L, 13L, 6L)
                ));

        RegionWiseWaterQuantityResponse response = service.getRegionWiseWaterQuantityByDepartment(1, 201, START, END);

        assertThat(response.getParentDepartmentId()).isEqualTo(201);
        verify(valueOperations, times(1)).set(eq(cacheKey), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getPeriodicWaterQuantityByDepartment_withNullScale_throws() {
        assertThatThrownBy(() -> service.getPeriodicWaterQuantityByDepartment(201, START, END, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale is required");
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegionByLgd_whenLgdMissing_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevel(101)).thenReturn(null);

        assertThatThrownBy(() -> service.getAverageWaterSupplyPerCurrentRegionByLgd(10, 101, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lgd_id not found");
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegionByDepartment_whenDepartmentMissing_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevel(201)).thenReturn(null);

        assertThatThrownBy(() -> service.getAverageWaterSupplyPerCurrentRegionByDepartment(10, 201, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_department_id not found");
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegionByLgd_valid_buildsChildResponse() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevel(101)).thenReturn(3);
        when(schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegionByLgd(10, 101, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                                null, null, 401, null, "Village A", 100L, 90L, 110L, 10000L, 2, new BigDecimal("50.0000"))
                ));
        when(schemeRegularityRepository.getRegionOwnWaterSupplyByLgd(10, 101, START, END))
                .thenReturn(new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                        10, null, 101, null, null, 100L, 90L, 110L, 10000L, 1, new BigDecimal("10000.0000")));
        when(dimTenantRepository.findById(10)).thenReturn(Optional.of(tenant(10, "mp")));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageWaterSupplyResponse response =
                service.getAverageWaterSupplyPerCurrentRegionByLgd(10, 101, START, END);

        assertThat(response.getTenantId()).isEqualTo(10);
        assertThat(response.getStateCode()).isEqualTo("mp");
        assertThat(response.getParentLgdLevel()).isEqualTo(3);
        assertThat(response.getChildRegionCount()).isEqualTo(1);
        assertThat(response.getChildRegions().getFirst().getLgdId()).isEqualTo(401);
        // The focal region's own deduped total is exposed separately for the region's headline figure.
        assertThat(response.getCurrentRegion().getLgdId()).isEqualTo(101);
        assertThat(response.getCurrentRegion().getSchemeCount()).isEqualTo(1);
    }

    @Test
    void getReadingSubmissionRateByLgd_cacheMiss_computesAndWritesCache() throws Exception {
        mockRedisValueOps();
        String key = ":reading_submission_rate:tenant:1:lgd:101:start:2026-01-01:end:2026-01-03:v3";
        when(valueOperations.get(key)).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(2);
        when(schemeRegularityRepository.getReadingSubmissionRateMetricsByLgd(1, 101, START, END))
                .thenReturn(new SchemeRegularityRepository.SchemeRegularityMetrics(2, 3));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        ReadingSubmissionRateResponse response = service.getReadingSubmissionRateByLgd(1, 101, START, END);

        assertThat(response.getParentLgdLevel()).isEqualTo(2);
        assertThat(response.getReadingSubmissionRate()).isEqualByComparingTo("0.5000");
        verify(valueOperations, times(1)).set(eq(key), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getAverageSchemeRegularityByDepartment_cacheMiss_returnsComputedResponse() throws Exception {
        mockRedisValueOps();
        String key = ":scheme_regularity:tenant:1:department:201:start:2026-01-01:end:2026-01-03:v2";
        when(valueOperations.get(key)).thenReturn(null);
        // 2 regular of 3 schemes = 0.6667.
        when(schemeRegularityRepository.getSchemeRegularityMetricsByDepartment(1, 201, START, END))
                .thenReturn(new SchemeRegularityRepository.SchemeRegularityMetrics(3, 4, 2));
        when(schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(1))
                .thenReturn(new BigDecimal("90"));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageSchemeRegularityResponse response =
                service.getAverageSchemeRegularityByDepartment(1, 201, START, END);

        assertThat(response.getParentDepartmentId()).isEqualTo(201);
        assertThat(response.getRegularSchemeCount()).isEqualTo(2);
        assertThat(response.getAverageRegularity()).isEqualByComparingTo("0.6667");
    }

    @Test
    void getAverageSchemeRegularityByDepartmentForChildRegions_aggregatesChildRows() throws Exception {
        mockRedisValueOps();
        String key = ":scheme_regularity:tenant:1:department:201:scope:child:start:2026-01-01:end:2026-01-03:v2";
        when(valueOperations.get(key)).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevelForTenant(1, 201)).thenReturn(2);
        when(schemeRegularityRepository.getChildSchemeRegularityMetricsByDepartment(1, 201, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics(
                                null, 301, "Dept-A", 2, 4, 2, new BigDecimal("1.0000")),
                        new SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics(
                                null, 302, "Dept-B", 1, 1, 0, new BigDecimal("0.0000"))
                ));
        when(schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(1))
                .thenReturn(new BigDecimal("90"));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageSchemeRegularityResponse response =
                service.getAverageSchemeRegularityByDepartmentForChildRegions(1, 201, START, END);

        assertThat(response.getChildRegionCount()).isEqualTo(2);
        assertThat(response.getSchemeCount()).isEqualTo(3);
        assertThat(response.getTotalSupplyDays()).isEqualTo(5);
        // Aggregate KPI = sum(regularSchemeCount) / sum(schemeCount) = 2 / 3 = 0.6667.
        assertThat(response.getRegularSchemeCount()).isEqualTo(2);
        assertThat(response.getAverageRegularity()).isEqualByComparingTo("0.6667");
    }

    @Test
    void getReadingSubmissionRateByDepartment_cacheMiss_returnsComputedResponse() throws Exception {
        mockRedisValueOps();
        String key = ":reading_submission_rate:tenant:1:department:201:start:2026-01-01:end:2026-01-03:v3";
        when(valueOperations.get(key)).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevelForTenant(1, 201)).thenReturn(2);
        when(schemeRegularityRepository.getReadingSubmissionRateMetricsByDepartment(1, 201, START, END))
                .thenReturn(new SchemeRegularityRepository.SchemeRegularityMetrics(2, 5));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        ReadingSubmissionRateResponse response =
                service.getReadingSubmissionRateByDepartment(1, 201, START, END);

        assertThat(response.getParentDepartmentLevel()).isEqualTo(2);
        assertThat(response.getReadingSubmissionRate()).isEqualByComparingTo("0.8333");
    }

    @Test
    void getReadingSubmissionRateByLgdForChildRegions_aggregatesChildRows() throws Exception {
        mockRedisValueOps();
        String key = ":reading_submission_rate:tenant:1:lgd:101:scope:child:start:2026-01-01:end:2026-01-03:v3";
        when(valueOperations.get(key)).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(2);
        when(schemeRegularityRepository.getChildReadingSubmissionRateMetricsByLgd(1, 101, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics(
                                401, null, "LGD-A", 1, 3, new BigDecimal("1.0000")),
                        new SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics(
                                402, null, "LGD-B", 2, 4, new BigDecimal("0.6667"))
                ));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        ReadingSubmissionRateResponse response =
                service.getReadingSubmissionRateByLgdForChildRegions(1, 101, START, END);

        assertThat(response.getChildRegionCount()).isEqualTo(2);
        assertThat(response.getSchemeCount()).isEqualTo(3);
        assertThat(response.getTotalSubmissionDays()).isEqualTo(7);
        assertThat(response.getReadingSubmissionRate()).isEqualByComparingTo("0.7778");
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegion_valid_returnsSchemeMetrics() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegion(10, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.SchemeWaterSupplyMetrics(
                                1, "Scheme-A", 100L, 90L, 110L, 1200L, 2, new BigDecimal("4.0000"))
                ));
        when(dimTenantRepository.findById(10)).thenReturn(Optional.of(tenant(10, "mp")));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageWaterSupplyResponse response =
                service.getAverageWaterSupplyPerCurrentRegion(10, START, END);

        assertThat(response.getTenantId()).isEqualTo(10);
        assertThat(response.getStateCode()).isEqualTo("mp");
        assertThat(response.getSchemeCount()).isEqualTo(1);
        assertThat(response.getSchemes()).hasSize(1);
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegionByDepartment_valid_buildsChildResponse() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevel(201)).thenReturn(2);
        when(schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegionByDepartment(10, 201, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                                null, null, null, 501, "Dept-1", 120L, 100L, 140L, 9000L, 3, new BigDecimal("3000.0000"))
                ));
        when(schemeRegularityRepository.getRegionOwnWaterSupplyByDepartment(10, 201, START, END))
                .thenReturn(new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                        10, null, null, 201, null, 120L, 100L, 140L, 9000L, 1, new BigDecimal("9000.0000")));
        when(dimTenantRepository.findById(10)).thenReturn(Optional.of(tenant(10, "mp")));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageWaterSupplyResponse response =
                service.getAverageWaterSupplyPerCurrentRegionByDepartment(10, 201, START, END);

        assertThat(response.getParentDepartmentLevel()).isEqualTo(2);
        assertThat(response.getChildRegionCount()).isEqualTo(1);
        assertThat(response.getChildRegions().getFirst().getDepartmentId()).isEqualTo(501);
        assertThat(response.getCurrentRegion().getDepartmentId()).isEqualTo(201);
        assertThat(response.getCurrentRegion().getSchemeCount()).isEqualTo(1);
    }

    @Test
    void getRegionWiseWaterQuantityByLgd_valid_mapsChildMetrics() {
        when(schemeRegularityRepository.getLgdLevel(101)).thenReturn(2);
        when(schemeRegularityRepository.getRegionWiseWaterQuantityByLgd(1, 101, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterQuantityMetrics(401, null, "LGD-A", 120L, 10L, 9L, 12L, 5L)
                ));

        var response = service.getRegionWiseWaterQuantityByLgd(1, 101, START, END);

        assertThat(response.getParentLgdId()).isEqualTo(101);
        assertThat(response.getChildRegionCount()).isEqualTo(1);
        assertThat(response.getChildRegions().getFirst().getWaterQuantity()).isEqualTo(120L);
    }

    @Test
    void getRegionWiseWaterQuantityByDepartment_valid_mapsChildMetrics() {
        when(schemeRegularityRepository.getDepartmentLevel(201)).thenReturn(2);
        when(schemeRegularityRepository.getRegionWiseWaterQuantityByDepartment(1, 201, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterQuantityMetrics(null, 501, "Dept-A", 150L, 11L, 10L, 13L, 6L)
                ));

        var response = service.getRegionWiseWaterQuantityByDepartment(1, 201, START, END);

        assertThat(response.getParentDepartmentId()).isEqualTo(201);
        assertThat(response.getChildRegionCount()).isEqualTo(1);
        assertThat(response.getChildRegions().getFirst().getDepartmentId()).isEqualTo(501);
    }

    @Test
    void getPeriodicWaterQuantityByDepartment_validMapsMetrics() {
        when(schemeRegularityRepository.getPeriodicWaterQuantityByDepartment(201, START, END, PeriodScale.DAY))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.PeriodicWaterQuantityMetrics(
                                START, START, "2026-01-01", new BigDecimal("22.1250"), 44L, 40L, 50L)
                ));

        PeriodicWaterQuantityResponse response =
                service.getPeriodicWaterQuantityByDepartment(201, START, END, PeriodScale.DAY);

        assertThat(response.getDepartmentId()).isEqualTo(201);
        assertThat(response.getPeriodCount()).isEqualTo(1);
        assertThat(response.getMetrics().getFirst().getAverageWaterQuantity()).isEqualByComparingTo("22.1250");
    }

    @Test
    void getOutageReasonSchemeCountByDepartment_mapsReasonAndChildRows() {
        when(schemeRegularityRepository.getDepartmentLevelForTenant(1, 201)).thenReturn(2);
        when(schemeRegularityRepository.getOutageReasonSchemeCountByDepartment(1, 201, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.OutageReasonSchemeCount("draught", 3)));
        when(schemeRegularityRepository.getChildRegionsByDepartment(1, 201))
                .thenReturn(List.of(new SchemeRegularityRepository.ChildRegionRef(null, 501, "Dept-A")));
        when(schemeRegularityRepository.getChildOutageReasonSchemeCountByDepartment(1, 201, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount(
                        null, 501, "no_electricity", 4
                )));

        OutageReasonSchemeCountResponse response =
                service.getOutageReasonSchemeCountByDepartment(1, 201, START, END);

        assertThat(response.getDepartmentId()).isEqualTo(201);
        assertThat(response.getOutageReasonSchemeCount()).containsExactlyEntriesOf(Map.of("draught", 3));
        assertThat(response.getChildRegions()).hasSize(1);
        assertThat(response.getChildRegions().getFirst().getOutageReasonSchemeCount()).containsEntry("no_electricity", 4);
    }

    @Test
    void getOutageReasonSchemeCountByDepartment_cacheKeyIncludesTenantId_avoidsTenantCollision() throws Exception {
        mockRedisValueOps();
        LocalDate start = LocalDate.of(2026, 3, 12);
        LocalDate end = LocalDate.of(2026, 4, 10);
        int parentDepartmentId = 1;

        String keyTenant17 = ":outage_reasons:tenant:17:parent_department:1:start:2026-03-12:end:2026-04-10:v1";
        String keyTenant79 = ":outage_reasons:tenant:79:parent_department:1:start:2026-03-12:end:2026-04-10:v1";

        OutageReasonSchemeCountResponse cachedTenant17 = OutageReasonSchemeCountResponse.builder()
                .departmentId(parentDepartmentId)
                .startDate(start)
                .endDate(end)
                .outageReasonSchemeCount(Map.of("no_water_supply", 1))
                .childRegionCount(0)
                .childRegions(List.of())
                .build();

        when(valueOperations.get(keyTenant17)).thenReturn("cached-17");
        when(objectMapper.readValue("cached-17", OutageReasonSchemeCountResponse.class)).thenReturn(cachedTenant17);

        when(valueOperations.get(keyTenant79)).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevelForTenant(79, parentDepartmentId)).thenReturn(1);
        when(schemeRegularityRepository.getOutageReasonSchemeCountByDepartment(79, parentDepartmentId, start, end)).thenReturn(List.of());
        when(schemeRegularityRepository.getChildRegionsByDepartment(79, parentDepartmentId)).thenReturn(List.of());
        when(schemeRegularityRepository.getChildOutageReasonSchemeCountByDepartment(79, parentDepartmentId, start, end)).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        OutageReasonSchemeCountResponse fromCache =
                service.getOutageReasonSchemeCountByDepartment(17, parentDepartmentId, start, end);
        assertThat(fromCache.getOutageReasonSchemeCount()).containsEntry("no_water_supply", 1);
        verify(schemeRegularityRepository, never()).getDepartmentLevelForTenant(17, parentDepartmentId);

        service.getOutageReasonSchemeCountByDepartment(79, parentDepartmentId, start, end);
        verify(valueOperations, times(1)).get(keyTenant17);
        verify(valueOperations, times(1)).get(keyTenant79);
    }

    @Test
    void getOutageReasonSchemeCountByUser_returnsReasonCountsFromTableValues() {
        when(schemeRegularityRepository.getOutageReasonSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.OutageReasonSchemeCount("motor_burnt", 2)));
        when(schemeRegularityRepository.getDailyOutageReasonSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.DailyOutageReasonSchemeCount(START, "no_electricity", 1),
                        new SchemeRegularityRepository.DailyOutageReasonSchemeCount(START.plusDays(1), "motor_burnt", 2)
                ));
        when(schemeRegularityRepository.getSchemeCountByUser(1, 11)).thenReturn(2);

        UserOutageReasonSchemeCountResponse response =
                service.getOutageReasonSchemeCountByUser(1, 11, START, END);

        assertThat(response.getUserId()).isEqualTo(11);
        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getOutageReasonSchemeCount())
                .containsExactlyEntriesOf(Map.of("motor_burnt", 2));
        assertThat(response.getDailyOutageReasonDistribution()).hasSize(3);
        assertThat(response.getDailyOutageReasonDistribution().get(0).getOutageReasonSchemeCount())
                .containsEntry("no_electricity", 1);
        assertThat(response.getDailyOutageReasonDistribution().get(1).getOutageReasonSchemeCount())
                .containsEntry("motor_burnt", 2);
        assertThat(response.getDailyOutageReasonDistribution().get(2).getOutageReasonSchemeCount()).isEmpty();
    }

    @Test
    void getOutageReasonSchemeCountByUserUuid_resolvesUserIdFromUuid() {
        when(dimUserRepository.findByUuid(USER_UUID))
                .thenReturn(Optional.of(DimUser.builder().userId(11).uuid(USER_UUID).build()));
        when(schemeRegularityRepository.getOutageReasonSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.OutageReasonSchemeCount("motor_burnt", 2)));
        when(schemeRegularityRepository.getDailyOutageReasonSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of());
        when(schemeRegularityRepository.getSchemeCountByUser(1, 11)).thenReturn(2);

        UserOutageReasonSchemeCountResponse response =
                service.getOutageReasonSchemeCountByUserUuid(1, USER_UUID, START, END);

        assertThat(response.getUserId()).isEqualTo(11);
        verify(dimUserRepository, times(1)).findByUuid(USER_UUID);
    }

    @Test
    void getNonSubmissionReasonSchemeCountByLgd_usesTableReasonValues() {
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(3);
        when(schemeRegularityRepository.getNonSubmissionReasonSchemeCountByLgd(1, 101, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.NonSubmissionReasonSchemeCount("no_operator", 4)));
        when(schemeRegularityRepository.getChildRegionsByLgd(1, 101))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionRef(401, null, "Village A"),
                        new SchemeRegularityRepository.ChildRegionRef(402, null, "Village B")
                ));
        when(schemeRegularityRepository.getChildNonSubmissionReasonSchemeCountByLgd(1, 101, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount(
                                401, null, "app_issue", 2),
                        new SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount(
                                999, null, "network_issue", 5)
                ));

        NonSubmissionReasonSchemeCountResponse response =
                service.getNonSubmissionReasonSchemeCountByLgd(1, 101, START, END);

        assertThat(response.getNonSubmissionReasonSchemeCount())
                .containsExactlyEntriesOf(Map.of("no_operator", 4));
        assertThat(response.getChildRegions()).hasSize(2);
        assertThat(response.getChildRegions().get(0).getNonSubmissionReasonSchemeCount())
                .containsExactlyEntriesOf(Map.of("app_issue", 2));
        assertThat(response.getChildRegions().get(1).getNonSubmissionReasonSchemeCount())
                .isEmpty();
    }

    @Test
    void getNonSubmissionReasonSchemeCountByDepartment_mapsReasonAndChildRows() {
        when(schemeRegularityRepository.getDepartmentLevelForTenant(1, 201)).thenReturn(2);
        when(schemeRegularityRepository.getNonSubmissionReasonSchemeCountByDepartment(1, 201, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.NonSubmissionReasonSchemeCount("device_issue", 3)));
        when(schemeRegularityRepository.getChildRegionsByDepartment(1, 201))
                .thenReturn(List.of(new SchemeRegularityRepository.ChildRegionRef(null, 501, "Dept-A")));
        when(schemeRegularityRepository.getChildNonSubmissionReasonSchemeCountByDepartment(1, 201, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount(
                        null, 501, "operator_absent", 4
                )));

        NonSubmissionReasonSchemeCountResponse response =
                service.getNonSubmissionReasonSchemeCountByDepartment(1, 201, START, END);

        assertThat(response.getDepartmentId()).isEqualTo(201);
        assertThat(response.getNonSubmissionReasonSchemeCount()).containsExactlyEntriesOf(Map.of("device_issue", 3));
        assertThat(response.getChildRegions()).hasSize(1);
        assertThat(response.getChildRegions().getFirst().getNonSubmissionReasonSchemeCount())
                .containsEntry("operator_absent", 4);
    }

    @Test
    void getNonSubmissionReasonSchemeCountByUser_returnsReasonCountsFromTableValues() {
        when(schemeRegularityRepository.getNonSubmissionReasonSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.NonSubmissionReasonSchemeCount("device_issue", 2)));
        when(schemeRegularityRepository.getDailyNonSubmissionReasonSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.DailyNonSubmissionReasonSchemeCount(START, "network_issue", 1),
                        new SchemeRegularityRepository.DailyNonSubmissionReasonSchemeCount(START.plusDays(1), "device_issue", 2)
                ));
        when(schemeRegularityRepository.getSchemeCountByUser(1, 11)).thenReturn(2);

        UserNonSubmissionReasonSchemeCountResponse response =
                service.getNonSubmissionReasonSchemeCountByUser(1, 11, START, END);

        assertThat(response.getUserId()).isEqualTo(11);
        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getNonSubmissionReasonSchemeCount())
                .containsExactlyEntriesOf(Map.of("device_issue", 2));
        assertThat(response.getDailyNonSubmissionReasonDistribution()).hasSize(3);
        assertThat(response.getDailyNonSubmissionReasonDistribution().get(0).getNonSubmissionReasonSchemeCount())
                .containsEntry("network_issue", 1);
        assertThat(response.getDailyNonSubmissionReasonDistribution().get(1).getNonSubmissionReasonSchemeCount())
                .containsEntry("device_issue", 2);
        assertThat(response.getDailyNonSubmissionReasonDistribution().get(2).getNonSubmissionReasonSchemeCount()).isEmpty();
    }

    @Test
    void getNonSubmissionReasonSchemeCountByUserUuid_resolvesUserIdFromUuid() {
        when(dimUserRepository.findByUuid(USER_UUID))
                .thenReturn(Optional.of(DimUser.builder().userId(11).uuid(USER_UUID).build()));
        when(schemeRegularityRepository.getNonSubmissionReasonSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of(new SchemeRegularityRepository.NonSubmissionReasonSchemeCount("device_issue", 2)));
        when(schemeRegularityRepository.getDailyNonSubmissionReasonSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of());
        when(schemeRegularityRepository.getSchemeCountByUser(1, 11)).thenReturn(2);

        UserNonSubmissionReasonSchemeCountResponse response =
                service.getNonSubmissionReasonSchemeCountByUserUuid(1, USER_UUID, START, END);

        assertThat(response.getUserId()).isEqualTo(11);
        verify(dimUserRepository, times(1)).findByUuid(USER_UUID);
    }

    @Test
    void getSubmissionStatusByUser_returnsCompliantAndAnomalousCounts() {
        when(schemeRegularityRepository.getSchemeCountByUser(1, 11)).thenReturn(2);
        when(schemeRegularityRepository.getSubmissionStatusCountByUser(1, 11, START, END))
                .thenReturn(new SchemeRegularityRepository.SubmissionStatusCount(4, 1));
        when(schemeRegularityRepository.getDailySubmissionSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.DailySubmissionSchemeCount(START, 1),
                        new SchemeRegularityRepository.DailySubmissionSchemeCount(START.plusDays(2), 2)
                ));

        UserSubmissionStatusResponse response = service.getSubmissionStatusByUser(1, 11, START, END);

        assertThat(response.getUserId()).isEqualTo(11);
        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getCompliantSubmissionCount()).isEqualTo(4);
        assertThat(response.getAnomalousSubmissionCount()).isEqualTo(1);
        assertThat(response.getDailySubmissionSchemeDistribution()).hasSize(3);
        assertThat(response.getDailySubmissionSchemeDistribution().get(0).getSubmittedSchemeCount()).isEqualTo(1);
        assertThat(response.getDailySubmissionSchemeDistribution().get(1).getSubmittedSchemeCount()).isEqualTo(0);
        assertThat(response.getDailySubmissionSchemeDistribution().get(2).getSubmittedSchemeCount()).isEqualTo(2);
    }

    @Test
    void getSubmissionStatusByUserUuid_resolvesUserIdFromUuid() {
        when(dimUserRepository.findByUuid(USER_UUID))
                .thenReturn(Optional.of(DimUser.builder().userId(11).uuid(USER_UUID).build()));
        when(schemeRegularityRepository.getSchemeCountByUser(1, 11)).thenReturn(2);
        when(schemeRegularityRepository.getSubmissionStatusCountByUser(1, 11, START, END))
                .thenReturn(new SchemeRegularityRepository.SubmissionStatusCount(4, 1));
        when(schemeRegularityRepository.getDailySubmissionSchemeCountByUser(1, 11, START, END))
                .thenReturn(List.of());

        UserSubmissionStatusResponse response =
                service.getSubmissionStatusByUserUuid(1, USER_UUID, START, END);

        assertThat(response.getUserId()).isEqualTo(11);
        verify(dimUserRepository, times(1)).findByUuid(USER_UUID);
    }

    @Test
    void getSubmissionStatusSummaryByLgd_returnsCountsFromRepository() {
        when(schemeRegularityRepository.getSchemeCountByLgd(1, 100)).thenReturn(2);
        when(schemeRegularityRepository.getSubmissionStatusCountByLgd(1, 100, START, END))
                .thenReturn(new SchemeRegularityRepository.SubmissionStatusCount(5, 1));

        SubmissionStatusSummaryResponse response =
                service.getSubmissionStatusSummaryByLgd(1, 100, START, END);

        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getCompliantSubmissionCount()).isEqualTo(5);
        assertThat(response.getAnomalousSubmissionCount()).isEqualTo(1);
    }

    @Test
    void getSubmissionStatusSummaryByLgd_cacheKeyIncludesTenantId_andWritesCache() throws Exception {
        mockRedisValueOps();
        String cacheKey = ":submission_status:summary:tenant:1:lgd:100:start:2026-01-01:end:2026-01-03:v2";
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");
        when(schemeRegularityRepository.getSchemeCountByLgd(1, 100)).thenReturn(2);
        when(schemeRegularityRepository.getSubmissionStatusCountByLgd(1, 100, START, END))
                .thenReturn(new SchemeRegularityRepository.SubmissionStatusCount(5, 1));

        SubmissionStatusSummaryResponse response = service.getSubmissionStatusSummaryByLgd(1, 100, START, END);

        assertThat(response.getSchemeCount()).isEqualTo(2);
        verify(valueOperations, times(1)).set(eq(cacheKey), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getSubmissionStatusSummaryByDepartment_returnsCountsFromRepository() {
        when(schemeRegularityRepository.getSchemeCountByDepartment(1, 200)).thenReturn(2);
        when(schemeRegularityRepository.getSubmissionStatusCountByDepartment(1, 200, START, END))
                .thenReturn(new SchemeRegularityRepository.SubmissionStatusCount(4, 0));

        SubmissionStatusSummaryResponse response =
                service.getSubmissionStatusSummaryByDepartment(1, 200, START, END);

        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getCompliantSubmissionCount()).isEqualTo(4);
        assertThat(response.getAnomalousSubmissionCount()).isEqualTo(0);
    }

    @Test
    void getOutageReasonSchemeCountByUser_withInvalidUser_throwsBadRequest() {
        assertThatThrownBy(() -> service.getOutageReasonSchemeCountByUser(1, 0, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user_id must be a positive integer");
    }

    @Test
    void getOutageReasonSchemeCountByUserUuid_withUnknownUuid_throwsBadRequest() {
        when(dimUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOutageReasonSchemeCountByUserUuid(1, USER_UUID, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No user found for uuid");
    }

    @Test
    void getSchemeStatusCountByDepartment_handlesNullCountsAsZero() {
        when(schemeRegularityRepository.getSchemeStatusCountByDepartment(1, 201))
                .thenReturn(new SchemeRegularityRepository.SchemeStatusCount(4, null));

        Map<String, Integer> result = service.getSchemeStatusCountByDepartment(1, 201);

        assertThat(result)
                .containsEntry("active_schemes_count", 4)
                .containsEntry("inactive_schemes_count", 0);
    }

    @Test
    void getSchemeRegionReportByLgd_buildsSchemeMetricsAndCounts() {
        when(schemeRegularityRepository.getSchemeRegionReportByLgd(1, 101, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.SchemeRegularityListMetrics(1, "Scheme A", 10001, 20001, 1, 2, 3, false),
                        new SchemeRegularityRepository.SchemeRegularityListMetrics(2, "Scheme B", 10002, 20002, 0, 0, 1, false)
                ));
        when(schemeRegularityRepository.getParentLgdCNameByLgd(1, 101)).thenReturn("Parent");
        when(schemeRegularityRepository.getParentLgdTitleByLgd(1, 101)).thenReturn("District");

        SchemeRegularityListResponse response = service.getSchemeRegionReportByLgd(1, 101, START, END, null, null);

        assertThat(response.getParentLgdId()).isEqualTo(101);
        assertThat(response.getDaysInRange()).isEqualTo(3);
        assertThat(response.getTotalSchemeCount()).isEqualTo(2);
        assertThat(response.getActiveSchemeCount()).isEqualTo(1);
        assertThat(response.getInactiveSchemeCount()).isEqualTo(1);
        assertThat(response.getSchemes()).hasSize(2);
        // Per-scheme reporting rate stays supplyDays/daysInRange (unchanged); isRegular is additive.
        assertThat(response.getSchemes().get(0).getAverageRegularity()).isEqualByComparingTo("0.6667");
        assertThat(response.getSchemes().get(0).getIsRegular()).isFalse();
        assertThat(response.getSchemes().get(0).getSubmissionRate()).isEqualByComparingTo("1.0000");
        assertThat(response.getSchemes().get(1).getStatus()).isEqualTo("inactive");
    }

    @Test
    void getSchemeRegionReportByDepartment_buildsSchemeMetricsAndCounts() {
        when(schemeRegularityRepository.getSchemeRegionReportByDepartment(1, 201, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.SchemeRegularityListMetrics(4, "Scheme D", 10004, 20004, 1, 1, 2, false)
                ));
        when(schemeRegularityRepository.getParentDepartmentCNameByDepartment(1, 201)).thenReturn("Dept");
        when(schemeRegularityRepository.getParentDepartmentTitleByDepartment(1, 201)).thenReturn("Division");

        SchemeRegularityListResponse response =
                service.getSchemeRegionReportByDepartment(1, 201, START, END, null, null);

        assertThat(response.getParentDepartmentId()).isEqualTo(201);
        assertThat(response.getTotalSchemeCount()).isEqualTo(1);
        assertThat(response.getActiveSchemeCount()).isEqualTo(1);
        assertThat(response.getInactiveSchemeCount()).isEqualTo(0);
        assertThat(response.getSchemes().getFirst().getAverageRegularity()).isEqualByComparingTo("0.3333");
        assertThat(response.getSchemes().getFirst().getSubmissionRate()).isEqualByComparingTo("0.6667");
    }

    @Test
    void getSchemeRegionReportByLgd_withPagination_returnsPagedSchemes() {
        when(schemeRegularityRepository.getSchemeRegionReportByLgd(1, 101, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.SchemeRegularityListMetrics(1, "Scheme A", 10001, 20001, 1, 2, 3, false),
                        new SchemeRegularityRepository.SchemeRegularityListMetrics(2, "Scheme B", 10002, 20002, 0, 0, 1, false),
                        new SchemeRegularityRepository.SchemeRegularityListMetrics(3, "Scheme C", 10003, 20003, 1, 3, 3, true)
                ));
        when(schemeRegularityRepository.getParentLgdCNameByLgd(1, 101)).thenReturn("Parent");
        when(schemeRegularityRepository.getParentLgdTitleByLgd(1, 101)).thenReturn("District");

        SchemeRegularityListResponse response = service.getSchemeRegionReportByLgd(1, 101, START, END, 2, 1);

        assertThat(response.getTotalSchemeCount()).isEqualTo(3);
        assertThat(response.getSchemeCountInResponse()).isEqualTo(1);
        assertThat(response.getSchemes()).hasSize(1);
        assertThat(response.getSchemes().getFirst().getSchemeId()).isEqualTo(2);
    }

    @Test
    void refreshNationalDashboard_computesAndWritesCache() throws Exception {
        mockRedisValueOps();
        String key = ":national:dashboard:start:2026-01-01:end:2026-01-03:v5";

        when(schemeRegularityRepository.getAverageWaterSupplyPerNation(START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                                1, "mp", null, null, "Madhya Pradesh", 120L, 110L, 140L, 64000L, 5, new BigDecimal("12800.0000"))
                ));
        when(schemeRegularityRepository.getTenantWiseSupplyDaysInEfficientRange(START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.TenantSupplyDaysInEfficientRange(1, 7L)
                ));
        when(schemeRegularityRepository.getStateWiseRegularityMetrics(START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.StateSchemeRegularityMetrics(
                                1, "mp", "Madhya Pradesh", 5, 12, 4)
                ));
        when(schemeRegularityRepository.getStateWiseReadingSubmissionMetrics(START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.StateReadingSubmissionMetrics(
                                1, "mp", "Madhya Pradesh", 5, 10)
                ));
        when(schemeRegularityRepository.getOverallOutageReasonSchemeCount(START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.OutageReasonSchemeCount("draught", 3)
                ));
        when(schemeRegularityRepository.getNationalDashboardTenantStateMetadata())
                .thenReturn(List.of(
                        new SchemeRegularityRepository.NationalDashboardTenantStateMetadata(1, 100, 1)
                ));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        NationalDashboardResponse response = service.refreshNationalDashboard(START, END);

        assertThat(response.getDaysInRange()).isEqualTo(3);
        assertThat(response.getStateWiseQuantityPerformance()).hasSize(1);
        assertThat(response.getStateWiseRegularity()).hasSize(1);
        assertThat(response.getStateWiseReadingSubmissionRate()).hasSize(1);
        assertThat(response.getStateWiseQuantityPerformance().getFirst().getLgdId()).isEqualTo(100);
        assertThat(response.getStateWiseQuantityPerformance().getFirst().getTenantStatus()).isEqualTo(1);
        assertThat(response.getStateWiseQuantityPerformance().getFirst().getSupplyDaysInEfficientRange()).isEqualTo(7L);
        assertThat(response.getStateWiseRegularity().getFirst().getLgdId()).isEqualTo(100);
        // National-dashboard KPI = regularSchemeCount / schemeCount = 4 / 5 = 0.8000.
        assertThat(response.getStateWiseRegularity().getFirst().getRegularSchemeCount()).isEqualTo(4);
        assertThat(response.getStateWiseRegularity().getFirst().getAverageRegularity()).isEqualByComparingTo("0.8000");
        assertThat(response.getStateWiseReadingSubmissionRate().getFirst().getTenantStatus()).isEqualTo(1);
        verify(valueOperations, times(1)).set(eq(key), eq("{json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getNationalDashboardBoundariesForApi_computesAndWritesCacheWhenMiss() throws Exception {
        mockRedisValueOps();
        String polygonGeoJson =
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}";
        com.fasterxml.jackson.databind.JsonNode boundaryNode = new ObjectMapper().readTree(polygonGeoJson);

        when(valueOperations.get(":national:dashboard:boundaries:v1")).thenReturn(null);
        when(schemeRegularityRepository.getNationalDashboardStateBoundaries())
                .thenReturn(List.of(
                        new SchemeRegularityRepository.NationalDashboardStateBoundary(
                                1, 100, 1, "mp", "Madhya Pradesh", polygonGeoJson)
                ));
        when(objectMapper.readTree(eq(polygonGeoJson))).thenReturn(boundaryNode);
        when(objectMapper.writeValueAsString(any())).thenReturn("{boundary-json}");

        var response = service.getNationalDashboardBoundariesForApi();

        assertThat(response.getStateWiseBoundaries()).hasSize(1);
        assertThat(response.getStateWiseBoundaries().getFirst().getBoundary().get("type").asText()).isEqualTo("Polygon");
        verify(valueOperations, times(1)).set(
                eq(":national:dashboard:boundaries:v1"), eq("{boundary-json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getNationalDashboardLevel2BoundariesForApi_computesAndWritesCacheWhenMiss() throws Exception {
        mockRedisValueOps();
        String polygonGeoJson =
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}";
        com.fasterxml.jackson.databind.JsonNode boundaryNode = new ObjectMapper().readTree(polygonGeoJson);

        when(valueOperations.get(":national:dashboard:boundaries:level2:v1")).thenReturn(null);
        when(schemeRegularityRepository.getNationalDashboardLevel2LgdBoundaries())
                .thenReturn(List.of(
                        new SchemeRegularityRepository.NationalDashboardLevel2LgdBoundary(
                                1, 101, 1, "mp", "Madhya Pradesh", "District-1", polygonGeoJson)
                ));
        when(objectMapper.readTree(eq(polygonGeoJson))).thenReturn(boundaryNode);
        when(objectMapper.writeValueAsString(any())).thenReturn("{boundary-json}");

        var response = service.getNationalDashboardLevel2BoundariesForApi();

        assertThat(response.getLgdLevel2Boundaries()).hasSize(1);
        assertThat(response.getLgdLevel2Boundaries().getFirst().getBoundary().get("type").asText()).isEqualTo("Polygon");
        verify(valueOperations, times(1)).set(
                eq(":national:dashboard:boundaries:level2:v1"), eq("{boundary-json}"), eq(Duration.ofHours(24)));
    }

    @Test
    void getAveragePerformanceScoreByLgd_valid_returnsScore() {
        BigDecimal expected = new BigDecimal("0.8500");
        when(schemeRegularityRepository.getAveragePerformanceScoreByLgd(101, START, END)).thenReturn(expected);

        BigDecimal result = service.getAveragePerformanceScoreByLgd(101, START, END);

        assertThat(result).isEqualByComparingTo("0.8500");
        verify(schemeRegularityRepository, times(1)).getAveragePerformanceScoreByLgd(101, START, END);
    }

    @Test
    void getAveragePerformanceScoreByDepartment_valid_returnsScore() {
        BigDecimal expected = new BigDecimal("0.7200");
        when(schemeRegularityRepository.getAveragePerformanceScoreByDepartment(201, START, END)).thenReturn(expected);

        BigDecimal result = service.getAveragePerformanceScoreByDepartment(201, START, END);

        assertThat(result).isEqualByComparingTo("0.7200");
        verify(schemeRegularityRepository, times(1)).getAveragePerformanceScoreByDepartment(201, START, END);
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegionForCurrentScope_nullsOutChildFields() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegion(10, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.SchemeWaterSupplyMetrics(
                                1, "Scheme-A", 100L, 90L, 110L, 1200L, 2, new BigDecimal("4.0000"))
                ));
        when(dimTenantRepository.findById(10)).thenReturn(Optional.of(tenant(10, "mp")));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageWaterSupplyResponse response =
                service.getAverageWaterSupplyPerCurrentRegionForCurrentScope(10, START, END);

        assertThat(response.getTenantId()).isEqualTo(10);
        assertThat(response.getChildRegionCount()).isNull();
        assertThat(response.getChildRegions()).isNull();
    }

    @Test
    void getAverageWaterSupplyPerNationForChildScope_nullsOutSchemeFields() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getAverageWaterSupplyPerNation(START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                                1, "mp", null, null, "Madhya Pradesh", 120L, 100L, 140L, 9000L, 3,
                                new BigDecimal("3000.0000"))
                ));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageWaterSupplyResponse response = service.getAverageWaterSupplyPerNationForChildScope(START, END);

        assertThat(response.getSchemeCount()).isNull();
        assertThat(response.getSchemes()).isNull();
        assertThat(response.getChildRegionCount()).isEqualTo(1);
    }

    @Test
    void getNationalDashboard_cacheHit_returnsFromCacheWithoutCallingRepository() throws Exception {
        mockRedisValueOps();
        String cacheKey = ":national:dashboard:start:2026-01-01:end:2026-01-03:v5";
        NationalDashboardResponse cached = NationalDashboardResponse.builder().daysInRange(3).build();
        when(valueOperations.get(cacheKey)).thenReturn("cached");
        when(objectMapper.readValue("cached", NationalDashboardResponse.class)).thenReturn(cached);

        NationalDashboardResponse result = service.getNationalDashboard(START, END);

        assertThat(result.getDaysInRange()).isEqualTo(3);
        verify(schemeRegularityRepository, never()).getAverageWaterSupplyPerNation(any(), any());
    }

    @Test
    void getNationalDashboardForApi_delegatesToNationalDashboard_cacheHit() throws Exception {
        mockRedisValueOps();
        String cacheKey = ":national:dashboard:start:2026-01-01:end:2026-01-03:v5";
        NationalDashboardResponse cached = NationalDashboardResponse.builder().daysInRange(3).build();
        when(valueOperations.get(cacheKey)).thenReturn("cached");
        when(objectMapper.readValue("cached", NationalDashboardResponse.class)).thenReturn(cached);

        NationalDashboardResponse result = service.getNationalDashboardForApi(START, END);

        assertThat(result.getDaysInRange()).isEqualTo(3);
        verify(schemeRegularityRepository, never()).getAverageWaterSupplyPerNation(any(), any());
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegionByLgdForChildScope_nullsOutSchemeFields() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevel(101)).thenReturn(2);
        when(schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegionByLgd(10, 101, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                                null, null, 401, null, "Village A", 100L, 90L, 110L, 10000L, 2,
                                new BigDecimal("50.0000"))
                ));
        when(schemeRegularityRepository.getRegionOwnWaterSupplyByLgd(10, 101, START, END))
                .thenReturn(new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                        10, null, 101, null, null, 100L, 90L, 110L, 10000L, 1, new BigDecimal("10000.0000")));
        when(dimTenantRepository.findById(10)).thenReturn(Optional.of(tenant(10, "mp")));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageWaterSupplyResponse response =
                service.getAverageWaterSupplyPerCurrentRegionByLgdForChildScope(10, 101, START, END);

        assertThat(response.getSchemeCount()).isNull();
        assertThat(response.getSchemes()).isNull();
        assertThat(response.getChildRegionCount()).isEqualTo(1);
        // scope=child still exposes the region's own deduped total for the header.
        assertThat(response.getCurrentRegion().getLgdId()).isEqualTo(101);
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegionByDepartmentForChildScope_nullsOutSchemeFields() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevel(201)).thenReturn(2);
        when(schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegionByDepartment(10, 201, START, END))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                                null, null, null, 501, "Dept-1", 120L, 100L, 140L, 9000L, 3,
                                new BigDecimal("3000.0000"))
                ));
        when(schemeRegularityRepository.getRegionOwnWaterSupplyByDepartment(10, 201, START, END))
                .thenReturn(new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                        10, null, null, 201, null, 120L, 100L, 140L, 9000L, 1, new BigDecimal("9000.0000")));
        when(dimTenantRepository.findById(10)).thenReturn(Optional.of(tenant(10, "mp")));
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        AverageWaterSupplyResponse response =
                service.getAverageWaterSupplyPerCurrentRegionByDepartmentForChildScope(10, 201, START, END);

        assertThat(response.getSchemeCount()).isNull();
        assertThat(response.getSchemes()).isNull();
        assertThat(response.getChildRegionCount()).isEqualTo(1);
        assertThat(response.getCurrentRegion().getDepartmentId()).isEqualTo(201);
    }

    @Test
    void getPeriodicSchemeRegularityByDepartment_mapsMetricsCorrectly() {
        LocalDate requestedEnd = LocalDate.of(2026, 1, 10);
        when(schemeRegularityRepository.getPeriodicSchemeRegularityByDepartment(
                        1, 201, START, requestedEnd, PeriodScale.WEEK))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.PeriodicSchemeRegularityMetrics(
                                LocalDate.of(2025, 12, 29),
                                LocalDate.of(2026, 1, 12),
                                2,
                                0L,
                                3,
                                1,
                                20L)));

        PeriodicSchemeRegularityResponse response =
                service.getPeriodicSchemeRegularityByDepartment(1, 201, START, requestedEnd, PeriodScale.WEEK);

        assertThat(response.getScale()).isEqualTo("week");
        assertThat(response.getDepartmentId()).isEqualTo(201);
        assertThat(response.getSchemeCount()).isEqualTo(2);
        assertThat(response.getPeriodCount()).isEqualTo(1);
        assertThat(response.getMetrics().getFirst().getPeriodStartDate()).isEqualTo(START);
        assertThat(response.getMetrics().getFirst().getPeriodEndDate()).isEqualTo(requestedEnd);
    }

    @Test
    void getReadingSubmissionRateByLgd_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":reading_submission_rate:tenant:1:lgd:101:start:2026-01-01:end:2026-01-03:v3";
        ReadingSubmissionRateResponse cached = ReadingSubmissionRateResponse.builder()
                .parentLgdId(101).schemeCount(5).build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", ReadingSubmissionRateResponse.class)).thenReturn(cached);

        ReadingSubmissionRateResponse result = service.getReadingSubmissionRateByLgd(1, 101, START, END);

        assertThat(result.getParentLgdId()).isEqualTo(101);
        verify(schemeRegularityRepository, never()).getReadingSubmissionRateMetricsByLgd(any(), any(), any(), any());
    }

    @Test
    void getAverageSchemeRegularityByDepartment_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":scheme_regularity:tenant:1:department:201:start:2026-01-01:end:2026-01-03:v2";
        AverageSchemeRegularityResponse cached = AverageSchemeRegularityResponse.builder()
                .parentDepartmentId(201).schemeCount(3).build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", AverageSchemeRegularityResponse.class)).thenReturn(cached);

        AverageSchemeRegularityResponse result = service.getAverageSchemeRegularityByDepartment(1, 201, START, END);

        assertThat(result.getParentDepartmentId()).isEqualTo(201);
        verify(schemeRegularityRepository, never()).getSchemeRegularityMetricsByDepartment(any(), any(), any(), any());
    }

    @Test
    void getAverageSchemeRegularityForChildRegions_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":scheme_regularity:tenant:1:lgd:101:scope:child:start:2026-01-01:end:2026-01-03:v2";
        AverageSchemeRegularityResponse cached = AverageSchemeRegularityResponse.builder()
                .lgdId(101).schemeCount(4).build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", AverageSchemeRegularityResponse.class)).thenReturn(cached);

        AverageSchemeRegularityResponse result = service.getAverageSchemeRegularityForChildRegions(1, 101, START, END);

        assertThat(result.getLgdId()).isEqualTo(101);
        verify(schemeRegularityRepository, never()).getLgdLevelForTenant(any(), any());
    }

    @Test
    void getAverageSchemeRegularityForChildRegions_nullLgdLevel_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(null);

        assertThatThrownBy(() -> service.getAverageSchemeRegularityForChildRegions(1, 101, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_lgd_id not found");
    }

    @Test
    void getAverageSchemeRegularityForChildRegions_lgdLevel6_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(6);

        assertThatThrownBy(() -> service.getAverageSchemeRegularityForChildRegions(1, 101, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No child LGD level");
    }

    @Test
    void getAverageSchemeRegularityForChildRegions_successPath_aggregatesChildRows() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(2);
        when(schemeRegularityRepository.getChildSchemeRegularityMetricsByLgd(eq(1), eq(101), any(), any()))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics(
                                201, null, "Region A", 5, 10, 3, new java.math.BigDecimal("0.6000"))));
        when(schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(1))
                .thenReturn(new BigDecimal("90"));

        AverageSchemeRegularityResponse result =
                service.getAverageSchemeRegularityForChildRegions(1, 101, START, END);

        assertThat(result.getSchemeCount()).isEqualTo(5);
        assertThat(result.getRegularSchemeCount()).isEqualTo(3);
        assertThat(result.getAverageRegularity()).isEqualByComparingTo("0.6000");
        assertThat(result.getChildRegionCount()).isEqualTo(1);
        assertThat(result.getScope()).isEqualTo("child");
    }

    @Test
    void getAverageSchemeRegularityByDepartmentForChildRegions_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":scheme_regularity:tenant:1:department:201:scope:child:start:2026-01-01:end:2026-01-03:v2";
        AverageSchemeRegularityResponse cached = AverageSchemeRegularityResponse.builder()
                .parentDepartmentId(201).schemeCount(4).build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", AverageSchemeRegularityResponse.class)).thenReturn(cached);

        AverageSchemeRegularityResponse result =
                service.getAverageSchemeRegularityByDepartmentForChildRegions(1, 201, START, END);

        assertThat(result.getParentDepartmentId()).isEqualTo(201);
        verify(schemeRegularityRepository, never()).getDepartmentLevelForTenant(any(), any());
    }

    @Test
    void getAverageSchemeRegularityByDepartmentForChildRegions_nullLevel_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevelForTenant(1, 201)).thenReturn(null);

        assertThatThrownBy(() -> service.getAverageSchemeRegularityByDepartmentForChildRegions(1, 201, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_department_id not found");
    }

    @Test
    void getAverageSchemeRegularityByDepartmentForChildRegions_level6_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevelForTenant(1, 201)).thenReturn(6);

        assertThatThrownBy(() -> service.getAverageSchemeRegularityByDepartmentForChildRegions(1, 201, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No child department level");
    }

    @Test
    void getReadingSubmissionRateByDepartment_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":reading_submission_rate:tenant:1:department:201:start:2026-01-01:end:2026-01-03:v3";
        ReadingSubmissionRateResponse cached = ReadingSubmissionRateResponse.builder()
                .parentDepartmentId(201).schemeCount(3).build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", ReadingSubmissionRateResponse.class)).thenReturn(cached);

        ReadingSubmissionRateResponse result = service.getReadingSubmissionRateByDepartment(1, 201, START, END);

        assertThat(result.getParentDepartmentId()).isEqualTo(201);
        verify(schemeRegularityRepository, never()).getReadingSubmissionRateMetricsByDepartment(any(), any(), any(), any());
    }

    @Test
    void getReadingSubmissionRateByLgdForChildRegions_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":reading_submission_rate:tenant:1:lgd:101:scope:child:start:2026-01-01:end:2026-01-03:v3";
        ReadingSubmissionRateResponse cached = ReadingSubmissionRateResponse.builder()
                .parentLgdId(101).schemeCount(4).build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", ReadingSubmissionRateResponse.class)).thenReturn(cached);

        ReadingSubmissionRateResponse result = service.getReadingSubmissionRateByLgdForChildRegions(1, 101, START, END);

        assertThat(result.getParentLgdId()).isEqualTo(101);
        verify(schemeRegularityRepository, never()).getLgdLevelForTenant(any(), any());
    }

    @Test
    void getReadingSubmissionRateByLgdForChildRegions_nullLgdLevel_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(null);

        assertThatThrownBy(() -> service.getReadingSubmissionRateByLgdForChildRegions(1, 101, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lgd_id not found");
    }

    @Test
    void getReadingSubmissionRateByLgdForChildRegions_lgdLevel6_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevelForTenant(1, 101)).thenReturn(6);

        assertThatThrownBy(() -> service.getReadingSubmissionRateByLgdForChildRegions(1, 101, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No child LGD level");
    }

    @Test
    void getReadingSubmissionRateByDepartmentForChildRegions_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":reading_submission_rate:tenant:1:department:201:scope:child:start:2026-01-01:end:2026-01-03:v3";
        ReadingSubmissionRateResponse cached = ReadingSubmissionRateResponse.builder()
                .parentDepartmentId(201).schemeCount(2).build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", ReadingSubmissionRateResponse.class)).thenReturn(cached);

        ReadingSubmissionRateResponse result =
                service.getReadingSubmissionRateByDepartmentForChildRegions(1, 201, START, END);

        assertThat(result.getParentDepartmentId()).isEqualTo(201);
        verify(schemeRegularityRepository, never()).getDepartmentLevelForTenant(any(), any());
    }

    @Test
    void getReadingSubmissionRateByDepartmentForChildRegions_nullLevel_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevelForTenant(1, 201)).thenReturn(null);

        assertThatThrownBy(() -> service.getReadingSubmissionRateByDepartmentForChildRegions(1, 201, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_department_id not found");
    }

    @Test
    void getReadingSubmissionRateByDepartmentForChildRegions_level6_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevelForTenant(1, 201)).thenReturn(6);

        assertThatThrownBy(() -> service.getReadingSubmissionRateByDepartmentForChildRegions(1, 201, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No child department level");
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegion_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        String key = ":water_supply:tenant:1:start:2026-01-01:end:2026-01-03:v4";
        AverageWaterSupplyResponse cached = AverageWaterSupplyResponse.builder()
                .tenantId(1).schemeCount(5).schemes(List.of()).childRegions(List.of()).build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", AverageWaterSupplyResponse.class)).thenReturn(cached);

        AverageWaterSupplyResponse result = service.getAverageWaterSupplyPerCurrentRegion(1, START, END);

        assertThat(result.getTenantId()).isEqualTo(1);
        verify(schemeRegularityRepository, never()).getAverageWaterSupplyPerCurrentRegion(any(), any(), any());
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegionByLgd_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn("cached");
        AverageWaterSupplyResponse cached = AverageWaterSupplyResponse.builder()
                .schemeCount(3).schemes(List.of()).childRegions(List.of()).build();
        when(objectMapper.readValue("cached", AverageWaterSupplyResponse.class)).thenReturn(cached);

        AverageWaterSupplyResponse result =
                service.getAverageWaterSupplyPerCurrentRegionByLgd(1, 101, START, END);

        assertThat(result.getSchemeCount()).isEqualTo(3);
        verify(schemeRegularityRepository, never()).getAverageWaterSupplyPerCurrentRegionByLgd(any(), any(), any(), any());
    }

    @Test
    void getAverageWaterSupplyPerCurrentRegionByDepartment_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn("cached");
        AverageWaterSupplyResponse cached = AverageWaterSupplyResponse.builder()
                .schemeCount(2).schemes(List.of()).childRegions(List.of()).build();
        when(objectMapper.readValue("cached", AverageWaterSupplyResponse.class)).thenReturn(cached);

        AverageWaterSupplyResponse result =
                service.getAverageWaterSupplyPerCurrentRegionByDepartment(1, 201, START, END);

        assertThat(result.getSchemeCount()).isEqualTo(2);
        verify(schemeRegularityRepository, never()).getAverageWaterSupplyPerCurrentRegionByDepartment(any(), any(), any(), any());
    }

    @Test
    void getRegionWiseWaterQuantityByLgd_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn("cached");
        RegionWiseWaterQuantityResponse cached = RegionWiseWaterQuantityResponse.builder()
                .parentLgdId(101).childRegionCount(2).childRegions(List.of()).build();
        when(objectMapper.readValue("cached", RegionWiseWaterQuantityResponse.class)).thenReturn(cached);

        RegionWiseWaterQuantityResponse result =
                service.getRegionWiseWaterQuantityByLgd(1, 101, START, END);

        assertThat(result.getParentLgdId()).isEqualTo(101);
        verify(schemeRegularityRepository, never()).getRegionWiseWaterQuantityByLgd(any(), any(), any(), any());
    }

    @Test
    void getRegionWiseWaterQuantityByLgd_nullLgdLevel_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getLgdLevel(101)).thenReturn(null);

        assertThatThrownBy(() -> service.getRegionWiseWaterQuantityByLgd(1, 101, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_lgd_id not found");
    }

    @Test
    void getRegionWiseWaterQuantityByDepartment_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn("cached");
        RegionWiseWaterQuantityResponse cached = RegionWiseWaterQuantityResponse.builder()
                .parentDepartmentId(201).childRegionCount(2).childRegions(List.of()).build();
        when(objectMapper.readValue("cached", RegionWiseWaterQuantityResponse.class)).thenReturn(cached);

        RegionWiseWaterQuantityResponse result =
                service.getRegionWiseWaterQuantityByDepartment(1, 201, START, END);

        assertThat(result.getParentDepartmentId()).isEqualTo(201);
        verify(schemeRegularityRepository, never()).getRegionWiseWaterQuantityByDepartment(any(), any(), any(), any());
    }

    @Test
    void getRegionWiseWaterQuantityByDepartment_nullDeptLevel_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getDepartmentLevel(201)).thenReturn(null);

        assertThatThrownBy(() -> service.getRegionWiseWaterQuantityByDepartment(1, 201, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_department_id not found");
    }

    @Test
    void getNationalDashboard_cacheMiss_computesAndCaches() throws Exception {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(schemeRegularityRepository.getAverageWaterSupplyPerNation(any(), any()))
                .thenReturn(List.of());
        when(schemeRegularityRepository.getTenantWiseSupplyDaysInEfficientRange(any(), any()))
                .thenReturn(List.of());
        when(schemeRegularityRepository.getStateWiseRegularityMetrics(any(), any()))
                .thenReturn(List.of());
        when(schemeRegularityRepository.getStateWiseReadingSubmissionMetrics(any(), any()))
                .thenReturn(List.of());
        when(schemeRegularityRepository.getOverallOutageReasonSchemeCount(any(), any()))
                .thenReturn(List.of());
        when(schemeRegularityRepository.getNationalDashboardTenantStateMetadata())
                .thenReturn(List.of());
        when(objectMapper.writeValueAsString(any())).thenReturn("{json}");

        NationalDashboardResponse result = service.getNationalDashboard(START, END);

        assertThat(result.getStartDate()).isEqualTo(START);
        verify(valueOperations, times(1)).set(any(), eq("{json}"), any());
    }

    @Test
    void getNationalDashboardBoundariesForApi_cacheHit_skipsRepository() throws Exception {
        mockRedisValueOps();
        NationalDashboardBoundaryResponse cached = NationalDashboardBoundaryResponse.builder()
                .stateWiseBoundaries(List.of()).build();
        when(valueOperations.get(":national:dashboard:boundaries:v1")).thenReturn("cached");
        when(objectMapper.readValue("cached", NationalDashboardBoundaryResponse.class)).thenReturn(cached);

        NationalDashboardBoundaryResponse result = service.getNationalDashboardBoundariesForApi();

        assertThat(result.getStateWiseBoundaries()).isEmpty();
        verify(schemeRegularityRepository, never()).getNationalDashboardStateBoundaries();
    }

    private static DimTenant tenant(Integer id, String stateCode) {
        DimTenant tenant = new DimTenant();
        tenant.setTenantId(id);
        tenant.setStateCode(stateCode);
        return tenant;
    }

    private void mockRedisValueOps() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }
}
