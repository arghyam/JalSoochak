package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PersonSchemeDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingDetailDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryWithMetricsDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;
import org.arghyam.jalsoochak.user.repository.PersonSchemeRepository;
import org.arghyam.jalsoochak.user.service.serviceImpl.PersonSchemeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonSchemeServiceImpl")
class PersonSchemeServiceImplTest {

    @Mock
    private PersonSchemeRepository repository;

    private PersonSchemeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PersonSchemeServiceImpl(repository);
    }

    @Nested
    @DisplayName("countSchemesByPerson")
    class CountSchemesByPerson {

        @Test
        @DisplayName("delegates to repository with resolved schema")
        void delegatesToRepository() {
            when(repository.countSchemesByPerson("tenant_mp", 10L, null)).thenReturn(5L);

            assertThat(service.countSchemesByPerson("mp", 10L, null)).isEqualTo(5L);
        }

        @Test
        @DisplayName("passes schemeName filter to repository")
        void passesSchemeName() {
            when(repository.countSchemesByPerson("tenant_mp", 10L, "Jal")).thenReturn(2L);

            assertThat(service.countSchemesByPerson("mp", 10L, "Jal")).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("listSchemesByPerson")
    class ListSchemesByPerson {

        @Test
        @DisplayName("returns paged schemes for person")
        void returnsPagedSchemes() {
            List<PersonSchemeDetailsDTO> rows = List.of(PersonSchemeDetailsDTO.builder().build());
            when(repository.listSchemesByPerson("tenant_mp", 10L, null, "schemeName", "asc", 0, 20))
                    .thenReturn(rows);
            when(repository.countSchemesByPerson("tenant_mp", 10L, null)).thenReturn(1L);

            PageResponseDTO<PersonSchemeDetailsDTO> page =
                    service.listSchemesByPerson("mp", 10L, null, "schemeName", "asc", 0, 20);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getTotalElements()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("getSchemeDetails")
    class GetSchemeDetails {

        @Test
        @DisplayName("returns scheme details when found")
        void returnsSchemeDetails() {
            SchemeDetailsWithReportingDTO dto = SchemeDetailsWithReportingDTO.builder().build();
            when(repository.getSchemeDetails("tenant_mp", 5L)).thenReturn(dto);

            assertThat(service.getSchemeDetails("mp", 5L)).isSameAs(dto);
        }

        @Test
        @DisplayName("returns null when not found")
        void returnsNullWhenNotFound() {
            when(repository.getSchemeDetails("tenant_mp", 99L)).thenReturn(null);

            assertThat(service.getSchemeDetails("mp", 99L)).isNull();
        }
    }

    @Nested
    @DisplayName("listSchemeReadings")
    class ListSchemeReadings {

        @Test
        @DisplayName("returns paged reading submissions")
        void returnsPagedReadings() {
            List<SchemeReadingSubmissionDTO> rows = List.of(SchemeReadingSubmissionDTO.builder().build());
            when(repository.listSchemeReadings("tenant_mp", 5L, 0, 20)).thenReturn(rows);
            when(repository.countSchemeReadings("tenant_mp", 5L)).thenReturn(1L);

            PageResponseDTO<SchemeReadingSubmissionDTO> page = service.listSchemeReadings("mp", 5L, 0, 20);

            assertThat(page.getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("listPumpOperatorsByPerson")
    class ListPumpOperatorsByPerson {

        @Test
        @DisplayName("returns paged pump operators for a person")
        void returnsPagedOperators() {
            List<PumpOperatorSummaryWithMetricsDTO> rows = List.of(
                    PumpOperatorSummaryWithMetricsDTO.builder().build()
            );
            when(repository.parseStatus(null)).thenReturn(null);
            when(repository.listPumpOperatorsByPerson(
                    "tenant_mp", 10L, null, null, null, null, null, "id", "desc", 0, 20))
                    .thenReturn(rows);
            when(repository.countPumpOperatorsByPerson(
                    "tenant_mp", 10L, null, null, null, null, null))
                    .thenReturn(1L);

            PageResponseDTO<PumpOperatorSummaryWithMetricsDTO> page =
                    service.listPumpOperatorsByPerson("mp", 10L, null, null, null, null, null, "id", "desc", 0, 20);

            assertThat(page.getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("listPumpOperatorReadings")
    class ListPumpOperatorReadings {

        @Test
        @DisplayName("returns paged readings for a pump operator")
        void returnsPagedReadings() {
            List<PumpOperatorReadingDetailDTO> rows = List.of(PumpOperatorReadingDetailDTO.builder().build());
            when(repository.listPumpOperatorReadings("tenant_mp", 5L, null, "readingAt", "desc", 0, 20))
                    .thenReturn(rows);
            when(repository.countPumpOperatorReadings("tenant_mp", 5L, null)).thenReturn(1L);

            PageResponseDTO<PumpOperatorReadingDetailDTO> page =
                    service.listPumpOperatorReadings("mp", 5L, null, "readingAt", "desc", 0, 20);

            assertThat(page.getContent()).hasSize(1);
        }
    }
}
