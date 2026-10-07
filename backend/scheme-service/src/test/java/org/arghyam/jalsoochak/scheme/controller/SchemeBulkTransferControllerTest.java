package org.arghyam.jalsoochak.scheme.controller;

import org.arghyam.jalsoochak.scheme.dto.ReportLinkResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeBulkTransferControllerTest {

    @Mock
    SchemeService schemeService;

    @InjectMocks
    SchemeBulkTransferController controller;

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
    void downloadEndpoints_delegateToService() {
        ReportLinkResponseDTO schemes = ReportLinkResponseDTO.builder().link("https://files/s.csv").build();
        ReportLinkResponseDTO mappings = ReportLinkResponseDTO.builder().link("https://files/m.csv").build();
        when(schemeService.downloadSchemesReport()).thenReturn(schemes);
        when(schemeService.downloadSchemeMappingsReport()).thenReturn(mappings);

        assertThat(controller.downloadSchemes().getBody()).isEqualTo(schemes);
        assertThat(controller.downloadSchemeMappings().getBody()).isEqualTo(mappings);
    }

    @Test
    void republishSchemeDimensions_delegatesToService() {
        when(schemeService.republishSchemeDimensions("as")).thenReturn(java.util.Map.of("schemes", 3, "failed", 0));

        var response = controller.republishSchemeDimensions("as");

        assertThat(response.getBody()).containsEntry("schemes", 3);
        verify(schemeService).republishSchemeDimensions("as");
    }
}
