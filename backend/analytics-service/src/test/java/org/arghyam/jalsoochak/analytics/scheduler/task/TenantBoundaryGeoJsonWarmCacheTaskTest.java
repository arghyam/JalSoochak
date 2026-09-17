package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Nightly warm-up of the tenant-boundaries Redis cache so the first dashboard request of the day is
 * not the one that pays for the polygon merge.
 *
 * <p>The sweep must be resilient: one tenant failing (missing LGD, Redis blip) cannot stop the rest
 * of the tenants from being warmed.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TenantBoundaryGeoJsonWarmCacheTask")
class TenantBoundaryGeoJsonWarmCacheTaskTest {

    @Mock
    private DimTenantRepository dimTenantRepository;
    @Mock
    private DimLgdLocationRepository dimLgdLocationRepository;
    @Mock
    private TenantDetailsService tenantDetailsService;

    @InjectMocks
    private TenantBoundaryGeoJsonWarmCacheTask task;

    private static DimTenant tenant(Integer id) {
        DimTenant tenant = new DimTenant();
        tenant.setTenantId(id);
        return tenant;
    }

    private static DimLgdLocation lgd(Integer lgdId) {
        DimLgdLocation location = new DimLgdLocation();
        location.setLgdId(lgdId);
        return location;
    }

    @Test
    void isNamedForTheSchedulerLog() {
        assertThat(task.taskName()).isEqualTo("tenant-boundaries-warm-cache");
    }

    @Test
    void warmsTheLevelOneBoundaryForEveryTenant() {
        when(dimTenantRepository.findByTenantIdGreaterThan(0))
                .thenReturn(List.of(tenant(1), tenant(2)));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(1, 1))
                .thenReturn(Optional.of(lgd(101)));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(2, 1))
                .thenReturn(Optional.of(lgd(201)));

        task.runTask();

        verify(tenantDetailsService).getTenantBoundaryGeoJson(1, 101);
        verify(tenantDetailsService).getTenantBoundaryGeoJson(2, 201);
    }

    @Test
    void doesNothingWhenThereAreNoTenants() {
        when(dimTenantRepository.findByTenantIdGreaterThan(0)).thenReturn(List.of());

        task.runTask();

        verifyNoInteractions(tenantDetailsService);
    }

    @Test
    void skipsATenantRowWithNoUsableTenantId() {
        when(dimTenantRepository.findByTenantIdGreaterThan(0))
                .thenReturn(Arrays.asList(null, tenant(null), tenant(0), tenant(-1)));

        task.runTask();

        verifyNoInteractions(tenantDetailsService);
        verify(dimLgdLocationRepository, never())
                .findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(anyInt(), anyInt());
    }

    @Test
    void skipsATenantWithNoLevelOneLocation() {
        when(dimTenantRepository.findByTenantIdGreaterThan(0)).thenReturn(List.of(tenant(1)));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(1, 1))
                .thenReturn(Optional.empty());

        task.runTask();

        verifyNoInteractions(tenantDetailsService);
    }

    @Test
    void skipsATenantWhoseLevelOneLocationHasNoUsableId() {
        when(dimTenantRepository.findByTenantIdGreaterThan(0))
                .thenReturn(List.of(tenant(1), tenant(2)));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(1, 1))
                .thenReturn(Optional.of(lgd(null)));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(2, 1))
                .thenReturn(Optional.of(lgd(0)));

        task.runTask();

        verifyNoInteractions(tenantDetailsService);
    }

    @Test
    void keepsWarmingTheRemainingTenantsAfterOneFails() {
        when(dimTenantRepository.findByTenantIdGreaterThan(0))
                .thenReturn(List.of(tenant(1), tenant(2)));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(1, 1))
                .thenThrow(new IllegalStateException("query failed"));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(2, 1))
                .thenReturn(Optional.of(lgd(201)));

        assertThatCode(() -> task.runTask()).doesNotThrowAnyException();

        verify(tenantDetailsService).getTenantBoundaryGeoJson(2, 201);
    }

    @Test
    void keepsWarmingTheRemainingTenantsAfterAWarmUpCallFails() {
        when(dimTenantRepository.findByTenantIdGreaterThan(0))
                .thenReturn(List.of(tenant(1), tenant(2)));
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(anyInt(), anyInt()))
                .thenReturn(Optional.of(lgd(101)));
        when(tenantDetailsService.getTenantBoundaryGeoJson(1, 101))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> task.runTask()).doesNotThrowAnyException();

        verify(tenantDetailsService).getTenantBoundaryGeoJson(2, 101);
    }
}
