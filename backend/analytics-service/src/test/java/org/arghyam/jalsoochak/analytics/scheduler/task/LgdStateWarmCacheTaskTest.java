package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LgdStateWarmCacheTaskTest {

    @Test
    void runTask_warmsCachesForStateLgds() {
        DimLgdLocationRepository dimLgdLocationRepository = mock(DimLgdLocationRepository.class);
        SchemeRegularityService schemeRegularityService = mock(SchemeRegularityService.class);
        LgdStateWarmCacheTask task = new LgdStateWarmCacheTask(dimLgdLocationRepository, schemeRegularityService);

        DimLgdLocation state = DimLgdLocation.builder()
                .lgdId(889100)
                .tenantId(99501)
                .lgdLevel(1)
                .build();
        when(dimLgdLocationRepository.findByLgdLevel(1)).thenReturn(List.of(state));

        task.runTask();

        verify(dimLgdLocationRepository, times(1)).findByLgdLevel(1);

        // parent_lgd_id style
        verify(schemeRegularityService, times(1))
                .getAverageSchemeRegularity(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class));
        verify(schemeRegularityService, times(1))
                .getAverageSchemeRegularityForChildRegions(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class));
        verify(schemeRegularityService, times(1))
                .getReadingSubmissionRateByLgd(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class));
        verify(schemeRegularityService, times(1))
                .getReadingSubmissionRateByLgdForChildRegions(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class));
        verify(schemeRegularityService, times(1))
                .getRegionWiseWaterQuantityByLgd(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class));
        verify(schemeRegularityService, times(1))
                .getOutageReasonSchemeCountByLgd(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class));
        verify(schemeRegularityService, times(1))
                .getNonSubmissionReasonSchemeCountByLgd(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class));
        verify(schemeRegularityService, times(1))
                .getSchemeStatusAndTopReportingByLgd(eq(889100), any(LocalDate.class), any(LocalDate.class), isNull());
        verify(schemeRegularityService, times(1))
                .getSchemeRegionReportByLgd(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class), eq(1), isNull());

        // lgd_id style + scale=day
        verify(schemeRegularityService, times(1))
                .getPeriodicWaterQuantityByLgdId(eq(889100), any(LocalDate.class), any(LocalDate.class), eq(PeriodScale.DAY));
        verify(schemeRegularityService, times(1))
                .getPeriodicSchemeRegularityByLgdId(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class), eq(PeriodScale.DAY));
        verify(schemeRegularityService, times(1))
                .getPeriodicOutageReasonSchemeCountByLgdId(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class), eq(PeriodScale.DAY));
        verify(schemeRegularityService, times(1))
                .getSubmissionStatusSummaryByLgd(eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class));
        verify(schemeRegularityService, times(1))
                .getSchemeStatusCountByLgd(eq(99501), eq(889100));

        // water-supply child scope needs tenant_id + lgd_id
        verify(schemeRegularityService, times(1))
                .getAverageWaterSupplyPerCurrentRegionByLgdForChildScope(
                        eq(99501), eq(889100), any(LocalDate.class), any(LocalDate.class));

        verifyNoMoreInteractions(schemeRegularityService);
    }

    @Test
    void runTask_skipsRowsMissingIds() {
        DimLgdLocationRepository dimLgdLocationRepository = mock(DimLgdLocationRepository.class);
        SchemeRegularityService schemeRegularityService = mock(SchemeRegularityService.class);
        LgdStateWarmCacheTask task = new LgdStateWarmCacheTask(dimLgdLocationRepository, schemeRegularityService);

        DimLgdLocation missingTenant = DimLgdLocation.builder().lgdId(1).tenantId(null).lgdLevel(1).build();
        DimLgdLocation missingLgd = DimLgdLocation.builder().lgdId(null).tenantId(1).lgdLevel(1).build();
        when(dimLgdLocationRepository.findByLgdLevel(1)).thenReturn(List.of(missingTenant, missingLgd));

        task.runTask();

        verify(dimLgdLocationRepository, times(1)).findByLgdLevel(1);
        verifyNoInteractions(schemeRegularityService);
    }
}

