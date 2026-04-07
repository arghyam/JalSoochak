package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.response.UserAlertTotalsResponse;
import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAlertTotalsServiceTest {

    @Mock
    private EscalationQueryService escalationQueryService;
    @Mock
    private AnomalyRepository anomalyRepository;
    @Mock
    private SchemeRegularityRepository schemeRegularityRepository;

    @InjectMocks
    private UserAlertTotalsService service;

    @Test
    void getTotals_withExplicitDates_returnsTotals() {
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);

        OffsetDateTime expectedFrom = start.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime expectedTo = end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        when(escalationQueryService.countEscalations(eq(10), eq(9001), eq(start), eq(end))).thenReturn(12L);
        when(anomalyRepository.countAnomaliesForMappedUserSchemesInRange(eq(10), eq(9001), eq(expectedFrom), eq(expectedTo)))
                .thenReturn(7L);
        when(schemeRegularityRepository.getSchemeCountByUser(eq(9001))).thenReturn(5);
        when(schemeRegularityRepository.getTotalWaterSuppliedByUserSchemes(eq(10), eq(9001), eq(start), eq(end)))
                .thenReturn(143200L);

        UserAlertTotalsResponse out = service.getTotals(10, 9001, start, end);

        assertThat(out.getTotalEscalationCount()).isEqualTo(12L);
        assertThat(out.getTotalAnomalyCount()).isEqualTo(7L);
        assertThat(out.getTotalMappedSchemeCount()).isEqualTo(5);
        assertThat(out.getTotalWaterSupplied()).isEqualTo(143200L);

        verify(escalationQueryService).countEscalations(10, 9001, start, end);
        verify(anomalyRepository).countAnomaliesForMappedUserSchemesInRange(10, 9001, expectedFrom, expectedTo);
        verify(schemeRegularityRepository).getSchemeCountByUser(9001);
        verify(schemeRegularityRepository).getTotalWaterSuppliedByUserSchemes(10, 9001, start, end);
    }

    @Test
    void getTotals_whenStartAfterEnd_throws() {
        LocalDate start = LocalDate.of(2026, 4, 2);
        LocalDate end = LocalDate.of(2026, 4, 1);

        assertThatThrownBy(() -> service.getTotals(10, 9001, start, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start_date");
    }
}

