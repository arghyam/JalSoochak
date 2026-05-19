package org.arghyam.jalsoochak.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.dto.request.StaffReportRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.ReportResponseDTO;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.exceptions.StorageException;
import org.arghyam.jalsoochak.user.service.StaffReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(org.arghyam.jalsoochak.user.exceptions.GlobalExceptionHandler.class)
@DisplayName("StaffReportController")
class StaffReportControllerTest {

    private static final String URL = "/api/v1/tenant/user/staff/reports";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AppProperties appProperties;
    @MockBean private StaffReportService staffReportService;
    @MockBean(name = "userSecurity")
    private org.arghyam.jalsoochak.user.config.UserSecurityEvaluator userSecurity;

    private static ReportResponseDTO sampleResponse(String format, boolean cached) {
        return ReportResponseDTO.builder()
                .reportId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .format(format)
                .generatedAt(OffsetDateTime.of(2026, 5, 19, 14, 22, 0, 0, ZoneOffset.UTC))
                .dataVersion(42L)
                .downloadUrl("https://minio.example/staff-reports/key?sig=abc")
                .urlExpiresAt(OffsetDateTime.of(2026, 5, 19, 15, 22, 0, 0, ZoneOffset.UTC))
                .cached(cached)
                .build();
    }

    @Nested
    @DisplayName("POST /api/v1/tenant/user/staff/reports")
    class Generate {

        @Test
        @DisplayName("returns 200 with download URL on CSV cache hit")
        void cacheHitCsvReturns200() throws Exception {
            when(staffReportService.generate(eq("mp"), eq(ReportFormat.CSV), any(), any()))
                    .thenReturn(sampleResponse("CSV", true));

            mockMvc.perform(post(URL)
                            .param("tenantCode", "mp")
                            .param("format", "CSV")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.format").value("CSV"))
                    .andExpect(jsonPath("$.data.cached").value(true))
                    .andExpect(jsonPath("$.data.downloadUrl").value(
                            "https://minio.example/staff-reports/key?sig=abc"))
                    .andExpect(jsonPath("$.data.dataVersion").value(42));
        }

        @Test
        @DisplayName("defaults format to CSV when query param omitted")
        void defaultFormatIsCsv() throws Exception {
            when(staffReportService.generate(eq("mp"), eq(ReportFormat.CSV), any(), any()))
                    .thenReturn(sampleResponse("CSV", false));

            mockMvc.perform(post(URL)
                            .param("tenantCode", "mp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            verify(staffReportService).generate(eq("mp"), eq(ReportFormat.CSV), any(), any());
        }

        @Test
        @DisplayName("passes XLSX format through when explicitly requested")
        void xlsxFormatRoutedThrough() throws Exception {
            when(staffReportService.generate(eq("mp"), eq(ReportFormat.XLSX), any(), any()))
                    .thenReturn(sampleResponse("XLSX", false));

            mockMvc.perform(post(URL)
                            .param("tenantCode", "mp")
                            .param("format", "xlsx")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.format").value("XLSX"));

            verify(staffReportService).generate(eq("mp"), eq(ReportFormat.XLSX), any(), any());
        }

        @Test
        @DisplayName("forwards request body filters to the service")
        void forwardsFilters() throws Exception {
            when(staffReportService.generate(eq("mp"), eq(ReportFormat.CSV), any(), any()))
                    .thenReturn(sampleResponse("CSV", false));

            String body = objectMapper.writeValueAsString(
                    new StaffReportRequestDTO(List.of("DISTRICT_OFFICER"), "ACTIVE", "anita"));

            mockMvc.perform(post(URL)
                            .param("tenantCode", "mp")
                            .param("format", "CSV")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            ArgumentCaptor<StaffReportRequestDTO> cap = ArgumentCaptor.forClass(StaffReportRequestDTO.class);
            verify(staffReportService).generate(eq("mp"), eq(ReportFormat.CSV), cap.capture(), any());
            assertThat(cap.getValue().roles()).containsExactly("DISTRICT_OFFICER");
            assertThat(cap.getValue().status()).isEqualTo("ACTIVE");
            assertThat(cap.getValue().name()).isEqualTo("anita");
        }

        @Test
        @DisplayName("returns 200 when request body is omitted")
        void omittedBodyAccepted() throws Exception {
            when(staffReportService.generate(eq("mp"), eq(ReportFormat.CSV), any(), any()))
                    .thenReturn(sampleResponse("CSV", false));

            mockMvc.perform(post(URL)
                            .param("tenantCode", "mp")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 400 when tenantCode is missing")
        void missingTenantCodeReturns400() throws Exception {
            mockMvc.perform(post(URL)
                            .param("format", "CSV")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verify(staffReportService, never()).generate(any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 400 for unsupported format value")
        void invalidFormatReturns400() throws Exception {
            mockMvc.perform(post(URL)
                            .param("tenantCode", "mp")
                            .param("format", "pdf")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Unsupported report format")));

            verify(staffReportService, never()).generate(any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 500 with masked message when storage fails")
        void storageFailureReturns500() throws Exception {
            when(staffReportService.generate(any(), any(), any(), any()))
                    .thenThrow(new StorageException("MinIO offline"));

            mockMvc.perform(post(URL)
                            .param("tenantCode", "mp")
                            .param("format", "CSV")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value(
                            "Report storage is currently unavailable"));
        }
    }
}
