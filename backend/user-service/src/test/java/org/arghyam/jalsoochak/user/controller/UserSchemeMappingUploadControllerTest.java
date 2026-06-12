package org.arghyam.jalsoochak.user.controller;

import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorUploadResponseDTO;
import org.arghyam.jalsoochak.user.service.PumpOperatorUploadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserSchemeMappingUploadController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("UserSchemeMappingUploadController Tests")
class UserSchemeMappingUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppProperties appProperties;

    @MockBean
    private PumpOperatorUploadService pumpOperatorUploadService;

    @Nested
    @DisplayName("POST /api/v1/state-admin/user-scheme-mappings/upload")
    class Upload {

        @Test
        @DisplayName("returns 200 with upload result for a valid file")
        void returns200ForValidUpload() throws Exception {
            PumpOperatorUploadResponseDTO response = PumpOperatorUploadResponseDTO.builder()
                    .totalRows(3).uploadedRows(3).skippedRows(0).message("Upload complete").build();
            when(pumpOperatorUploadService.uploadUserSchemeMappings(any(), anyString())).thenReturn(response);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "mappings.csv", MediaType.TEXT_PLAIN_VALUE, "header\nrow1".getBytes());

            mockMvc.perform(multipart("/api/v1/state-admin/user-scheme-mappings/upload")
                            .file(file)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.uploadedRows").value(3));
        }

        @Test
        @DisplayName("propagates exceptions thrown by the service")
        void propagatesServiceException() throws Exception {
            when(pumpOperatorUploadService.uploadUserSchemeMappings(any(), anyString()))
                    .thenThrow(new RuntimeException("mapping upload failed"));

            MockMultipartFile file = new MockMultipartFile(
                    "file", "bad.csv", MediaType.TEXT_PLAIN_VALUE, "data".getBytes());

            mockMvc.perform(multipart("/api/v1/state-admin/user-scheme-mappings/upload")
                            .file(file)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                    .andExpect(status().isInternalServerError());
        }
    }
}
