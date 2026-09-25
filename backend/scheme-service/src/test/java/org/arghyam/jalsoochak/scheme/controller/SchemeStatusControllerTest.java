package org.arghyam.jalsoochak.scheme.controller;

import org.arghyam.jalsoochak.scheme.dto.SchemeStatusUpdateRequestDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusesResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeStatusControllerTest {

    @Mock
    SchemeService schemeService;

    @InjectMocks
    SchemeStatusController controller;

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
