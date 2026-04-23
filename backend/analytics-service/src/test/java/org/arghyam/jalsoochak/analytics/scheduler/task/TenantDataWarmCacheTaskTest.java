package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TenantDataWarmCacheTaskTest {

    @Test
    void runTask_warmsTenantDataForEachTenantLevel1Lgd() {
        DimTenantRepository dimTenantRepository = mock(DimTenantRepository.class);
        DimLgdLocationRepository dimLgdLocationRepository = mock(DimLgdLocationRepository.class);
        TenantDetailsService tenantDetailsService = mock(TenantDetailsService.class);
        DefaultAnalyticsDateWindowProvider windowProvider =
                new DefaultAnalyticsDateWindowProvider("Asia/Kolkata", 30);

        TenantDataWarmCacheTask task = new TenantDataWarmCacheTask(
                dimTenantRepository,
                dimLgdLocationRepository,
                tenantDetailsService,
                windowProvider
        );

        DimTenant tenantA = new DimTenant();
        tenantA.setTenantId(17);
        DimTenant tenantB = new DimTenant();
        tenantB.setTenantId(18);
        when(dimTenantRepository.findByTenantIdGreaterThan(0)).thenReturn(List.of(tenantA, tenantB));

        DimLgdLocation tenantALevel1 = DimLgdLocation.builder().tenantId(17).lgdLevel(1).lgdId(1).build();
        DimLgdLocation tenantBLevel1 = DimLgdLocation.builder().tenantId(18).lgdLevel(1).lgdId(1).build();
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(17, 1))
                .thenReturn(Optional.of(tenantALevel1));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(18, 1))
                .thenReturn(Optional.of(tenantBLevel1));

        task.runTask();

        verify(dimTenantRepository, times(1)).findByTenantIdGreaterThan(0);
        verify(dimLgdLocationRepository, times(1)).findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(17, 1);
        verify(dimLgdLocationRepository, times(1)).findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(18, 1);

        // Dates are anchored to yesterday in the configured zone, so just verify the method is invoked.
        verify(tenantDetailsService, times(1))
                .getTenantDetailsWithAggregatedMetrics(eq(17), eq(1), any(LocalDate.class), any(LocalDate.class));
        verify(tenantDetailsService, times(1))
                .getTenantDetailsWithAggregatedMetrics(eq(18), eq(1), any(LocalDate.class), any(LocalDate.class));
        verifyNoMoreInteractions(tenantDetailsService);
    }

    @Test
    void runTask_usesInclusiveYesterdayAnchoredWindow() {
        DimTenantRepository dimTenantRepository = mock(DimTenantRepository.class);
        DimLgdLocationRepository dimLgdLocationRepository = mock(DimLgdLocationRepository.class);
        TenantDetailsService tenantDetailsService = mock(TenantDetailsService.class);
        DefaultAnalyticsDateWindowProvider windowProvider =
                new DefaultAnalyticsDateWindowProvider("Asia/Kolkata", 30);

        TenantDataWarmCacheTask task = new TenantDataWarmCacheTask(
                dimTenantRepository,
                dimLgdLocationRepository,
                tenantDetailsService,
                windowProvider
        );

        DimTenant tenant = new DimTenant();
        tenant.setTenantId(17);
        when(dimTenantRepository.findByTenantIdGreaterThan(0)).thenReturn(List.of(tenant));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(17, 1))
                .thenReturn(Optional.of(DimLgdLocation.builder().tenantId(17).lgdLevel(1).lgdId(1).build()));

        task.runTask();

        ZoneId zone = ZoneId.of("Asia/Kolkata");
        LocalDate expectedEnd = LocalDate.now(zone).minusDays(1);
        LocalDate expectedStart = expectedEnd.minusDays(29);

        verify(tenantDetailsService, times(1))
                .getTenantDetailsWithAggregatedMetrics(17, 1, expectedStart, expectedEnd);
    }

    @Test
    void runTask_skipsTenantsWithoutLevel1Lgd() {
        DimTenantRepository dimTenantRepository = mock(DimTenantRepository.class);
        DimLgdLocationRepository dimLgdLocationRepository = mock(DimLgdLocationRepository.class);
        TenantDetailsService tenantDetailsService = mock(TenantDetailsService.class);
        DefaultAnalyticsDateWindowProvider windowProvider =
                new DefaultAnalyticsDateWindowProvider("Asia/Kolkata", 30);

        TenantDataWarmCacheTask task = new TenantDataWarmCacheTask(
                dimTenantRepository,
                dimLgdLocationRepository,
                tenantDetailsService,
                windowProvider
        );

        DimTenant tenant = new DimTenant();
        tenant.setTenantId(17);
        when(dimTenantRepository.findByTenantIdGreaterThan(0)).thenReturn(List.of(tenant));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(17, 1))
                .thenReturn(Optional.empty());

        task.runTask();

        verifyNoInteractions(tenantDetailsService);
    }
}

