package org.arghyam.jalsoochak.scheme.controller;

import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusBreakdownDTO;
import org.arghyam.jalsoochak.scheme.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeQueryControllerTest {

    @Mock
    SchemeService schemeService;

    @InjectMocks
    SchemeQueryController controller;

    @Test
    void listSchemes_usesFallbackQueryParams() {
        PageResponseDTO<SchemeDTO> page = PageResponseDTO.of(List.of(), 0, 0, 20);
        when(schemeService.listSchemes("ka", 0, 20, "id", "desc", null, null, null,
                List.of("ongoing"), List.of("operative")))
                .thenReturn(page);

        PageResponseDTO<SchemeDTO> body = controller.listSchemes(
                "ka", 0, 20, "id", "desc",
                null, null, null,
                List.of(), List.of("ongoing"),
                null, List.of("operative")
        ).getBody();

        assertThat(body).isEqualTo(page);
    }

    @Test
    void listSchemes_passesMultiValuedStatusFiltersThrough() {
        PageResponseDTO<SchemeDTO> page = PageResponseDTO.of(List.of(), 0, 0, 20);
        when(schemeService.listSchemes("ka", 0, 20, "id", "desc", null, null, null,
                List.of("Ongoing", "Completed"), List.of("1", "2")))
                .thenReturn(page);

        PageResponseDTO<SchemeDTO> body = controller.listSchemes(
                "ka", 0, 20, "id", "desc",
                null, null, null,
                List.of("Ongoing", "Completed"), null,
                List.of("1", "2"), null
        ).getBody();

        assertThat(body).isEqualTo(page);
    }

    @Test
    void listSchemeMappings_andCounts_delegateToService() {
        PageResponseDTO<SchemeMappingDTO> mappings = PageResponseDTO.of(List.of(), 0, 0, 20);
        SchemeStatusBreakdownDTO byStatus = SchemeStatusBreakdownDTO.builder().totalSchemes(6).build();

        when(schemeService.listSchemeMappings("ka", 0, 20, "id", "desc", "name",
                List.of("1"), List.of("2"), "123", "sub"))
                .thenReturn(mappings);
        when(schemeService.getSchemeStatusCounts("ka")).thenReturn(byStatus);

        assertThat(controller.listSchemeMappings("ka", 0, 20, "id", "desc", null, "name",
                List.of("1"), List.of("2"), "123", "sub").getBody())
                .isEqualTo(mappings);
        assertThat(controller.getSchemeStatusCounts("ka").getBody()).isEqualTo(byStatus);
    }
}
