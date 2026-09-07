package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.enums.SchemeStatus;
import org.arghyam.jalsoochak.analytics.repository.SchemeStatusMaintenanceRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeStatusSchedulerService.SchemeStatusSyncResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeStatusSchedulerServiceImplTest {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @Mock
    private SchemeStatusMaintenanceRepository schemeStatusMaintenanceRepository;

    @InjectMocks
    private SchemeStatusSchedulerServiceImpl schemeStatusSchedulerService;

    @Test
    void syncSchemeStatusBySubmissionRecency_marksSchemesUsingThirtyDayThreshold() {
        LocalDate today = LocalDate.now(IST_ZONE);
        LocalDate cutoff = today.minusDays(30);
        when(schemeStatusMaintenanceRepository.findLastSubmittedDateByScheme(1))
                .thenReturn(List.of(
                        row(101, today.minusDays(5)),
                        row(102, cutoff),
                        row(103, today.minusDays(31)),
                        row(104, null)
                ));

        SchemeStatusSyncResult result = schemeStatusSchedulerService.syncSchemeStatusBySubmissionRecency(30);

        assertThat(result.totalSchemes()).isEqualTo(4);
        assertThat(result.activeMarkedCount()).isEqualTo(2);
        assertThat(result.inactiveMarkedCount()).isEqualTo(2);
        verify(schemeStatusMaintenanceRepository).updateSchemeStatusBySchemeIds(
                eq(SchemeStatus.ACTIVE.getCode()),
                eq(List.of(101, 102)));
        verify(schemeStatusMaintenanceRepository).updateSchemeStatusBySchemeIds(
                eq(SchemeStatus.INACTIVE.getCode()),
                eq(List.of(103, 104)));
    }

    @Test
    void syncSchemeStatusBySubmissionRecency_withNoSchemes_skipsUpdates() {
        when(schemeStatusMaintenanceRepository.findLastSubmittedDateByScheme(1))
                .thenReturn(List.of());

        SchemeStatusSyncResult result = schemeStatusSchedulerService.syncSchemeStatusBySubmissionRecency(30);

        assertThat(result.totalSchemes()).isEqualTo(0);
        assertThat(result.activeMarkedCount()).isEqualTo(0);
        assertThat(result.inactiveMarkedCount()).isEqualTo(0);
        verify(schemeStatusMaintenanceRepository, never())
                .updateSchemeStatusBySchemeIds(eq(SchemeStatus.ACTIVE.getCode()), anyCollection());
        verify(schemeStatusMaintenanceRepository, never())
                .updateSchemeStatusBySchemeIds(eq(SchemeStatus.INACTIVE.getCode()), anyCollection());
    }

    private static SchemeStatusMaintenanceRepository.SchemeLastSubmissionProjection row(
            Integer schemeId, LocalDate lastSubmittedDate) {
        return new SchemeStatusMaintenanceRepository.SchemeLastSubmissionProjection() {
            @Override
            public Integer getSchemeId() {
                return schemeId;
            }

            @Override
            public LocalDate getLastSubmittedDate() {
                return lastSubmittedDate;
            }
        };
    }
}
