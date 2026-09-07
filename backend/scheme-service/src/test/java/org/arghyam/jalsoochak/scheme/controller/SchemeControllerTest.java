package org.arghyam.jalsoochak.scheme.controller;

import org.arghyam.jalsoochak.scheme.dto.SchemeCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusUpdateRequestDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusesResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeControllerTest {

    @Mock
    SchemeService schemeService;

    @InjectMocks
    SchemeController controller;

    @Test
    void listSchemes_usesFallbackQueryParams() {
        PageResponseDTO<SchemeDTO> page = PageResponseDTO.of(List.of(), 0, 0, 20);
        when(schemeService.listSchemes("ka", 0, 20, "id", "desc", null, null, null, "ongoing", "operative", null))
                .thenReturn(page);

        PageResponseDTO<SchemeDTO> body = controller.listSchemes(
                "ka", 0, 20, "id", "desc",
                null, null, null,
                " ", "ongoing",
                null, "operative",
                null
        ).getBody();

        assertThat(body).isEqualTo(page);
    }

    @Test
    void listSchemeMappings_andCounts_delegateToService() {
        PageResponseDTO<SchemeMappingDTO> mappings = PageResponseDTO.of(List.of(), 0, 0, 20);
        SchemeCountsDTO counts = SchemeCountsDTO.builder().activeSchemes(4).inactiveSchemes(2).build();
        SchemeStatusCountsDTO byStatus = SchemeStatusCountsDTO.builder().totalSchemes(6).build();

        when(schemeService.listSchemeMappings("ka", 0, 20, "id", "desc", "name", "1", "2", "active", "123", "sub"))
                .thenReturn(mappings);
        when(schemeService.getSchemeCounts("ka")).thenReturn(counts);
        when(schemeService.getSchemeStatusCounts("ka")).thenReturn(byStatus);

        assertThat(controller.listSchemeMappings("ka", 0, 20, "id", "desc", null, "name", "1", "2", "active", "123", "sub").getBody())
                .isEqualTo(mappings);
        assertThat(controller.getSchemeCounts("ka").getBody()).isEqualTo(counts);
        assertThat(controller.getSchemeStatusCounts("ka").getBody()).isEqualTo(byStatus);
    }

    @Test
    void uploadEndpoints_delegateToService() {
        MockMultipartFile file = new MockMultipartFile("file", "f.csv", "text/csv", "a".getBytes());
        SchemeUploadResponseDTO response = SchemeUploadResponseDTO.builder().message("ok").build();
        when(schemeService.uploadSchemes(file)).thenReturn(response);
        when(schemeService.uploadSchemeMappings(file)).thenReturn(response);

        assertThat(controller.uploadSchemes(file).getBody()).isEqualTo(response);
        assertThat(controller.uploadSchemeMappings(file).getBody()).isEqualTo(response);
        verify(schemeService).uploadSchemes(file);
        verify(schemeService).uploadSchemeMappings(file);
    }

    @Test
    void updateSchemeStatuses_delegatesToService() {
        SchemeStatusUpdateRequestDTO request = new SchemeStatusUpdateRequestDTO();
        request.setWorkStatus("Completed");
        request.setOperatingStatus("Operative");

        assertThat(controller.updateSchemeStatuses("ka", 11, request).getStatusCode().value()).isEqualTo(204);
        verify(schemeService).updateSchemeStatuses("ka", 11, request);
    }

    @Test
    void getSchemeStatuses_delegatesToService() {
        SchemeStatusesResponseDTO response = SchemeStatusesResponseDTO.builder()
                .workStatus(2)
                .operatingStatus(1)
                .build();
        when(schemeService.getSchemeStatuses(22, 11)).thenReturn(response);

        SchemeStatusesResponseDTO body = controller.getSchemeStatuses(11, 22).getBody();

        assertThat(body).isEqualTo(response);
        verify(schemeService).getSchemeStatuses(22, 11);
    }
}
