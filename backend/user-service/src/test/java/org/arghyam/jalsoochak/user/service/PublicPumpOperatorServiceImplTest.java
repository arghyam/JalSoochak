package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsWithComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemePumpOperatorsDTO;
import org.arghyam.jalsoochak.user.repository.PublicPumpOperatorRepository;
import org.arghyam.jalsoochak.user.service.serviceImpl.PublicPumpOperatorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicPumpOperatorServiceImpl")
class PublicPumpOperatorServiceImplTest {

    @Mock
    private PublicPumpOperatorRepository repository;

    private PublicPumpOperatorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PublicPumpOperatorServiceImpl(repository);
    }

    @Nested
    @DisplayName("getPumpOperatorDetails")
    class GetPumpOperatorDetails {

        @Test
        @DisplayName("returns DTO when operator found")
        void returnsDto() {
            PumpOperatorDetailsDTO dto = PumpOperatorDetailsDTO.builder().id(1L).build();
            when(repository.findPumpOperatorById("tenant_mp", 1L, 5L, null, null)).thenReturn(dto);

            assertThat(service.getPumpOperatorDetails("mp", 1L, 5L, null, null)).isSameAs(dto);
        }

        @Test
        @DisplayName("returns DTO when schemeId is omitted")
        void returnsDtoWithoutSchemeId() {
            PumpOperatorDetailsDTO dto = PumpOperatorDetailsDTO.builder().id(1L).build();
            when(repository.findPumpOperatorById("tenant_mp", 1L, null, null, null)).thenReturn(dto);

            assertThat(service.getPumpOperatorDetails("mp", 1L, null, null, null)).isSameAs(dto);
        }

        @Test
        @DisplayName("throws 404 when operator not found")
        void throwsWhenNotFound() {
            when(repository.findPumpOperatorById("tenant_mp", 99L, 5L, null, null)).thenReturn(null);

            assertThatThrownBy(() -> service.getPumpOperatorDetails("mp", 99L, 5L, null, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    @DisplayName("getReadingCompliance")
    class GetReadingCompliance {

        @Test
        @DisplayName("returns DTO when compliance data found")
        void returnsDto() {
            PumpOperatorReadingComplianceDTO dto = PumpOperatorReadingComplianceDTO.builder().build();
            when(repository.getReadingCompliance("tenant_mp", 1L)).thenReturn(dto);

            assertThat(service.getReadingCompliance("mp", 1L)).isSameAs(dto);
        }

        @Test
        @DisplayName("throws 404 when compliance data not found")
        void throwsWhenNotFound() {
            when(repository.getReadingCompliance("tenant_mp", 99L)).thenReturn(null);

            assertThatThrownBy(() -> service.getReadingCompliance("mp", 99L))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("getPumpOperatorDetailsWithCompliance")
    class GetDetailsWithCompliance {

        @Test
        @DisplayName("combines details and compliance into a single DTO")
        void combinesDetailsAndCompliance() {
            PumpOperatorDetailsDTO details = PumpOperatorDetailsDTO.builder().id(1L).build();
            PumpOperatorReadingComplianceDTO compliance = PumpOperatorReadingComplianceDTO.builder().build();
            when(repository.findPumpOperatorById("tenant_mp", 1L)).thenReturn(details);
            when(repository.getReadingCompliance("tenant_mp", 1L)).thenReturn(compliance);

            PumpOperatorDetailsWithComplianceDTO result = service.getPumpOperatorDetailsWithCompliance("mp", 1L);

            assertThat(result.details()).isSameAs(details);
            assertThat(result.readingCompliance()).isSameAs(compliance);
        }
    }

    @Nested
    @DisplayName("listReadingCompliance")
    class ListReadingCompliance {

        @Test
        @DisplayName("returns paginated compliance rows")
        void returnsPaginatedRows() {
            List<PumpOperatorReadingComplianceRowDTO> rows = List.of(
                    PumpOperatorReadingComplianceRowDTO.builder().build()
            );
            when(repository.listReadingCompliance("tenant_mp", 0, 10)).thenReturn(rows);
            when(repository.countReadingCompliance("tenant_mp")).thenReturn(1L);

            PageResponseDTO<PumpOperatorReadingComplianceRowDTO> page = service.listReadingCompliance("mp", 0, 10);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getTotalElements()).isEqualTo(1L);
        }

        @Test
        @DisplayName("clamps size below 1 to 1")
        void clampsSizeBelow1() {
            when(repository.listReadingCompliance("tenant_mp", 0, 1)).thenReturn(List.of());
            when(repository.countReadingCompliance("tenant_mp")).thenReturn(0L);

            PageResponseDTO<PumpOperatorReadingComplianceRowDTO> page = service.listReadingCompliance("mp", 0, 0);

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getSize()).isEqualTo(1);
            verify(repository).listReadingCompliance("tenant_mp", 0, 1);
        }

        @Test
        @DisplayName("clamps size above 100 to 100")
        void clampsSizeAbove100() {
            when(repository.listReadingCompliance("tenant_mp", 0, 100)).thenReturn(List.of());
            when(repository.countReadingCompliance("tenant_mp")).thenReturn(0L);

            PageResponseDTO<PumpOperatorReadingComplianceRowDTO> page = service.listReadingCompliance("mp", 0, 500);

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getSize()).isEqualTo(100);
            verify(repository).listReadingCompliance("tenant_mp", 0, 100);
        }

        @Test
        @DisplayName("skips the row query when the requested page starts past the last row")
        void skipsRowQueryForOutOfRangePage() {
            when(repository.countReadingCompliance("tenant_mp")).thenReturn(40L);

            assertThatThrownBy(() -> service.listReadingCompliance("mp", 999, 20))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("out of range");

            verify(repository, never()).listReadingCompliance(anyString(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("still queries the first page of an empty tenant")
        void queriesFirstPageWhenTotalIsZero() {
            when(repository.listReadingCompliance("tenant_mp", 0, 20)).thenReturn(List.of());
            when(repository.countReadingCompliance("tenant_mp")).thenReturn(0L);

            PageResponseDTO<PumpOperatorReadingComplianceRowDTO> page = service.listReadingCompliance("mp", 0, 20);

            assertThat(page.getContent()).isEmpty();
            verify(repository).listReadingCompliance("tenant_mp", 0, 20);
        }

        @Test
        @DisplayName("computes offsets past Integer.MAX_VALUE without overflowing")
        void computesLargeOffsetWithoutOverflow() {
            long expectedOffset = 200_000_000L * 20L;
            when(repository.countReadingCompliance("tenant_mp")).thenReturn(expectedOffset + 5);
            when(repository.listReadingCompliance("tenant_mp", expectedOffset, 20)).thenReturn(List.of());

            service.listReadingCompliance("mp", 200_000_000, 20);

            verify(repository).listReadingCompliance("tenant_mp", expectedOffset, 20);
        }
    }

    @Nested
    @DisplayName("listSchemeReadingCompliance")
    class ListBySchemeWithCompliance {

        @Test
        @DisplayName("returns paginated scheme compliance rows")
        void returnsPaginatedRows() {
            List<SchemeReadingComplianceRowDTO> rows = List.of(
                    SchemeReadingComplianceRowDTO.builder().build()
            );
            when(repository.listSchemeReadingCompliance("tenant_mp", 5L, 9L, null, null, 0, 20)).thenReturn(rows);
            when(repository.countSchemeReadingCompliance("tenant_mp", 5L, 9L, null, null)).thenReturn(1L);

            PageResponseDTO<SchemeReadingComplianceRowDTO> page =
                    service.listSchemeReadingCompliance("mp", 5L, 9L, null, null, 0, 20);

            assertThat(page.getContent()).hasSize(1);
        }

        /**
         * The listing pages over readings while the count once counted operators, so an operator's
         * second reading sat on a page the shorter total declared out of range and could never be
         * fetched. Both now speak in readings.
         */
        @Test
        @DisplayName("serves the second reading of a single operator at page 1 of size 1")
        void returnsSecondReadingOfSingleOperator() {
            List<SchemeReadingComplianceRowDTO> secondReading = List.of(
                    SchemeReadingComplianceRowDTO.builder().id(9L).build()
            );
            when(repository.countSchemeReadingCompliance("tenant_mp", 5L, 9L, null, null)).thenReturn(2L);
            when(repository.listSchemeReadingCompliance("tenant_mp", 5L, 9L, null, null, 1, 1))
                    .thenReturn(secondReading);

            PageResponseDTO<SchemeReadingComplianceRowDTO> page =
                    service.listSchemeReadingCompliance("mp", 5L, 9L, null, null, 1, 1);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getTotalElements()).isEqualTo(2);
            assertThat(page.getTotalPages()).isEqualTo(2);
        }

        @Test
        @DisplayName("skips the row query when the requested page starts past the last row")
        void skipsRowQueryForOutOfRangePage() {
            when(repository.countSchemeReadingCompliance("tenant_mp", 5L, null, null, null)).thenReturn(40L);

            assertThatThrownBy(() -> service.listSchemeReadingCompliance("mp", 5L, null, null, null, 999, 20))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("out of range");

            verify(repository, never()).listSchemeReadingCompliance(
                    anyString(), anyLong(), any(), any(), any(), anyLong(), anyInt());
        }
    }

    @Nested
    @DisplayName("listPumpOperatorsByScheme")
    class ListByScheme {

        @Test
        @DisplayName("delegates to repository with resolved schema")
        void delegatesToRepository() {
            List<SchemePumpOperatorsDTO> result = List.of(SchemePumpOperatorsDTO.builder().build());
            when(repository.listPumpOperatorsByScheme("tenant_mp", List.of(1L), null, null, null))
                    .thenReturn(result);

            assertThat(service.listPumpOperatorsByScheme("mp", List.of(1L), null, null, null))
                    .hasSize(1);
        }
    }
}
