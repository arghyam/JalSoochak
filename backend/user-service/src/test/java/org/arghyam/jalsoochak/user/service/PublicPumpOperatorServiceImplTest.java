package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsWithComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSchemeComplianceRowDTO;
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
            when(repository.findPumpOperatorById("tenant_mp", 1L)).thenReturn(dto);

            assertThat(service.getPumpOperatorDetails("mp", 1L)).isSameAs(dto);
        }

        @Test
        @DisplayName("throws 404 when operator not found")
        void throwsWhenNotFound() {
            when(repository.findPumpOperatorById("tenant_mp", 99L)).thenReturn(null);

            assertThatThrownBy(() -> service.getPumpOperatorDetails("mp", 99L))
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
        }

        @Test
        @DisplayName("clamps size above 100 to 100")
        void clampsSizeAbove100() {
            when(repository.listReadingCompliance("tenant_mp", 0, 100)).thenReturn(List.of());
            when(repository.countReadingCompliance("tenant_mp")).thenReturn(0L);

            PageResponseDTO<PumpOperatorReadingComplianceRowDTO> page = service.listReadingCompliance("mp", 0, 500);
            assertThat(page.getContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("listPumpOperatorsBySchemeWithCompliance")
    class ListBySchemeWithCompliance {

        @Test
        @DisplayName("returns paginated scheme compliance rows")
        void returnsPaginatedRows() {
            List<PumpOperatorSchemeComplianceRowDTO> rows = List.of(
                    PumpOperatorSchemeComplianceRowDTO.builder().build()
            );
            when(repository.listPumpOperatorsBySchemeWithCompliance("tenant_mp", 5L, 0, 20)).thenReturn(rows);
            when(repository.countPumpOperatorsBySchemeWithCompliance("tenant_mp", 5L)).thenReturn(1L);

            PageResponseDTO<PumpOperatorSchemeComplianceRowDTO> page =
                    service.listPumpOperatorsBySchemeWithCompliance("mp", 5L, 0, 20);

            assertThat(page.getContent()).hasSize(1);
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
