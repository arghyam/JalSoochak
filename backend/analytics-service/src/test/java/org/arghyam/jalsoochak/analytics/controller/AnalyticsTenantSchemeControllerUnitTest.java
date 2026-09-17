package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.TenantBoundaryGeoJsonResponse;
import org.arghyam.jalsoochak.analytics.dto.response.TenantDetailsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.TenantPerformanceScoreResponse;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimScheme;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimSchemeRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tenant/scheme dimension endpoints.
 *
 * <p>Each endpoint validates its date window and its mutually exclusive parent filter before
 * delegating, answering 400 for a bad request and 500 for anything unexpected — the dashboard relies
 * on that split to tell "you asked wrong" from "we broke".</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AnalyticsTenantSchemeController — direct invocation")
class AnalyticsTenantSchemeControllerUnitTest {

    private static final int TENANT = 1;
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 2, 28);

    @Mock
    private DimTenantRepository dimTenantRepository;
    @Mock
    private DimLgdLocationRepository dimLgdLocationRepository;
    @Mock
    private DimSchemeRepository dimSchemeRepository;
    @Mock
    private FactMeterReadingRepository meterReadingRepository;
    @Mock
    private TenantDetailsService tenantDetailsService;
    @Mock
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    @InjectMocks
    private AnalyticsTenantSchemeController controller;

    private static DimLgdLocation lgd(int lgdId) {
        DimLgdLocation location = new DimLgdLocation();
        location.setLgdId(lgdId);
        return location;
    }

    @BeforeEach
    void stubDefaults() {
        when(defaultAnalyticsDateWindowProvider.defaultWindow())
                .thenReturn(new DefaultAnalyticsDateWindowProvider.DateWindow(START, END));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(anyInt(), eq(1)))
                .thenReturn(Optional.of(lgd(101)));
    }

    @Nested
    @DisplayName("GET /tenants")
    class Tenants {

        @Test
        void returnsEveryTenantAboveTheNationalSentinel() {
            List<DimTenant> tenants = List.of(new DimTenant(), new DimTenant());
            when(dimTenantRepository.findByTenantIdGreaterThan(0)).thenReturn(tenants);

            var response = controller.getTenants();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().isSuccess()).isTrue();
            assertThat(response.getBody().getData()).isSameAs(tenants);
        }

        @Test
        void answersFiveHundredWhenTheLookupFails() {
            when(dimTenantRepository.findByTenantIdGreaterThan(0))
                    .thenThrow(new IllegalStateException("db down"));

            var response = controller.getTenants();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(response.getBody().getData()).isNull();
        }
    }

    @Nested
    @DisplayName("GET /tenant_data")
    class TenantData {

        @Test
        void delegatesToTheLgdScopedServiceByDefault() {
            TenantDetailsResponse expected = TenantDetailsResponse.builder().build();
            when(tenantDetailsService.getTenantDetailsWithAggregatedMetrics(TENANT, 101, START, END))
                    .thenReturn(expected);

            var response = controller.getTenantDetails(TENANT, null, null, START, END);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isSameAs(expected);
        }

        @Test
        void resolvesTheTenantsLevelOneLgdWhenNoParentIsGiven() {
            controller.getTenantDetails(TENANT, null, null, START, END);

            verify(dimLgdLocationRepository).findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(TENANT, 1);
            verify(tenantDetailsService).getTenantDetailsWithAggregatedMetrics(TENANT, 101, START, END);
        }

        @Test
        void delegatesToTheDepartmentScopedServiceWhenADepartmentParentIsGiven() {
            TenantDetailsResponse expected = TenantDetailsResponse.builder().build();
            when(tenantDetailsService.getTenantDetailsByParentDepartmentWithAggregatedMetrics(
                    TENANT, 501, START, END)).thenReturn(expected);

            var response = controller.getTenantDetails(TENANT, null, 501, START, END);

            assertThat(response.getBody().getData()).isSameAs(expected);
            verify(dimLgdLocationRepository, never())
                    .findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(anyInt(), anyInt());
        }

        @Test
        void fillsInTheDefaultWindowWhenNeitherDateIsGiven() {
            controller.getTenantDetails(TENANT, 101, null, null, null);

            verify(tenantDetailsService).getTenantDetailsWithAggregatedMetrics(TENANT, 101, START, END);
        }

        @Test
        void rejectsAHalfSuppliedDateWindow() {
            assertThat(controller.getTenantDetails(TENANT, 101, null, START, null).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.getTenantDetails(TENANT, 101, null, null, END).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsAnInvertedDateWindow() {
            var response = controller.getTenantDetails(TENANT, 101, null, END, START);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().isSuccess()).isFalse();
        }

        @Test
        void rejectsBothParentFiltersAtOnce() {
            var response = controller.getTenantDetails(TENANT, 101, 501, START, END);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsATenantWithNoLevelOneLgd() {
            when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(anyInt(), anyInt()))
                    .thenReturn(Optional.empty());

            assertThat(controller.getTenantDetails(TENANT, null, null, START, END).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void answersFiveHundredForAnUnexpectedServiceFailure() {
            when(tenantDetailsService.getTenantDetailsWithAggregatedMetrics(anyInt(), anyInt(), any(), any()))
                    .thenThrow(new IllegalStateException("db down"));

            assertThat(controller.getTenantDetails(TENANT, 101, null, START, END).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("GET /tenant_boundaries")
    class TenantBoundaries {

        @Test
        void delegatesToTheLgdScopedService() {
            TenantBoundaryGeoJsonResponse expected = TenantBoundaryGeoJsonResponse.builder().build();
            when(tenantDetailsService.getTenantBoundaryGeoJson(TENANT, 101)).thenReturn(expected);

            var response = controller.getTenantBoundaryGeoJson(TENANT, 101, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isSameAs(expected);
        }

        @Test
        void delegatesToTheDepartmentScopedService() {
            TenantBoundaryGeoJsonResponse expected = TenantBoundaryGeoJsonResponse.builder().build();
            when(tenantDetailsService.getTenantBoundaryGeoJsonByParentDepartment(TENANT, 501))
                    .thenReturn(expected);

            assertThat(controller.getTenantBoundaryGeoJson(TENANT, null, 501).getBody().getData())
                    .isSameAs(expected);
        }

        @Test
        void resolvesTheTenantsLevelOneLgdWhenNoParentIsGiven() {
            controller.getTenantBoundaryGeoJson(TENANT, null, null);

            verify(tenantDetailsService).getTenantBoundaryGeoJson(TENANT, 101);
        }

        @Test
        void rejectsBothParentFiltersAtOnce() {
            assertThat(controller.getTenantBoundaryGeoJson(TENANT, 101, 501).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsATenantWithNoLevelOneLgd() {
            when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(anyInt(), anyInt()))
                    .thenReturn(Optional.empty());

            assertThat(controller.getTenantBoundaryGeoJson(TENANT, null, null).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void answersFiveHundredForAnUnexpectedServiceFailure() {
            when(tenantDetailsService.getTenantBoundaryGeoJson(anyInt(), anyInt()))
                    .thenThrow(new IllegalStateException("redis down"));

            assertThat(controller.getTenantBoundaryGeoJson(TENANT, 101, null).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("GET /tenant_performance_score")
    class PerformanceScore {

        @Test
        void delegatesToTheLgdScopedService() {
            TenantPerformanceScoreResponse expected = TenantPerformanceScoreResponse.builder().build();
            when(tenantDetailsService.getTenantPerformanceScoreByParentLgd(TENANT, 101, START, END))
                    .thenReturn(expected);

            var response = controller.getTenantPerformanceScore(TENANT, 101, null, START, END);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isSameAs(expected);
        }

        @Test
        void delegatesToTheDepartmentScopedService() {
            TenantPerformanceScoreResponse expected = TenantPerformanceScoreResponse.builder().build();
            when(tenantDetailsService.getTenantPerformanceScoreByParentDepartment(TENANT, 501, START, END))
                    .thenReturn(expected);

            assertThat(controller.getTenantPerformanceScore(TENANT, null, 501, START, END).getBody().getData())
                    .isSameAs(expected);
        }

        @Test
        void fillsInTheDefaultWindowWhenNeitherDateIsGiven() {
            controller.getTenantPerformanceScore(TENANT, 101, null, null, null);

            verify(tenantDetailsService).getTenantPerformanceScoreByParentLgd(TENANT, 101, START, END);
        }

        @Test
        void rejectsAHalfSuppliedOrInvertedDateWindow() {
            assertThat(controller.getTenantPerformanceScore(TENANT, 101, null, START, null).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.getTenantPerformanceScore(TENANT, 101, null, END, START).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsBothParentFiltersAtOnce() {
            assertThat(controller.getTenantPerformanceScore(TENANT, 101, 501, START, END).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void resolvesTheTenantsLevelOneLgdWhenNoParentIsGiven() {
            controller.getTenantPerformanceScore(TENANT, null, null, START, END);

            verify(tenantDetailsService).getTenantPerformanceScoreByParentLgd(TENANT, 101, START, END);
        }

        @Test
        void answersFiveHundredForAnUnexpectedServiceFailure() {
            when(tenantDetailsService.getTenantPerformanceScoreByParentLgd(anyInt(), anyInt(), any(), any()))
                    .thenThrow(new IllegalStateException("db down"));

            assertThat(controller.getTenantPerformanceScore(TENANT, 101, null, START, END).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("GET /schemes")
    class Schemes {

        @Test
        void scopesToATenantWhenOneIsGiven() {
            List<DimScheme> schemes = List.of(new DimScheme());
            when(dimSchemeRepository.findByTenantId(TENANT)).thenReturn(schemes);

            assertThat(controller.getSchemes(TENANT).getBody().getData()).isSameAs(schemes);
            verify(dimSchemeRepository, never()).findAll();
        }

        @Test
        void returnsEverySchemeWhenNoTenantIsGiven() {
            List<DimScheme> schemes = List.of(new DimScheme());
            when(dimSchemeRepository.findAll()).thenReturn(schemes);

            assertThat(controller.getSchemes(null).getBody().getData()).isSameAs(schemes);
        }

        @Test
        void answersFiveHundredWhenTheLookupFails() {
            when(dimSchemeRepository.findAll()).thenThrow(new IllegalStateException("db down"));

            assertThat(controller.getSchemes(null).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("GET /meter-readings")
    class MeterReadings {

        /**
         * This endpoint is descending-ordered: it validates {@code start_date} is strictly AFTER
         * {@code end_date}, and its default window is applied in that same reversed order. Pinned here
         * because the parameter names read the other way round.
         */
        @Test
        void requiresStartDateStrictlyAfterEndDate() {
            List<FactMeterReading> readings = List.of(new FactMeterReading());
            when(meterReadingRepository.findByTenantIdAndSchemeIdAndReadingDateBetween(
                    TENANT, 7, END, START)).thenReturn(readings);

            var response = controller.getMeterReadings(TENANT, 7, END, START);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isSameAs(readings);
        }

        @Test
        void rejectsAnAscendingWindow() {
            assertThat(controller.getMeterReadings(TENANT, 7, START, END).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsEqualDates() {
            assertThat(controller.getMeterReadings(TENANT, 7, START, START).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void appliesTheDefaultWindowReversed() {
            controller.getMeterReadings(TENANT, 7, null, null);

            verify(meterReadingRepository)
                    .findByTenantIdAndSchemeIdAndReadingDateBetween(TENANT, 7, END, START);
        }

        @Test
        void rejectsAHalfSuppliedDateWindow() {
            assertThat(controller.getMeterReadings(TENANT, 7, END, null).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.getMeterReadings(TENANT, 7, null, START).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void answersFiveHundredWhenTheLookupFails() {
            when(meterReadingRepository.findByTenantIdAndSchemeIdAndReadingDateBetween(
                    anyInt(), anyInt(), any(), any())).thenThrow(new IllegalStateException("db down"));

            assertThat(controller.getMeterReadings(TENANT, 7, END, START).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
