package org.arghyam.jalsoochak.user.controller;

import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PersonSchemeDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsWithComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingDetailDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSchemeComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryWithMetricsDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.arghyam.jalsoochak.user.service.PublicPumpOperatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicPumpOperatorController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicPumpOperatorController Tests")
class PublicPumpOperatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppProperties appProperties;

    @MockBean
    private PublicPumpOperatorService publicPumpOperatorService;

    @MockBean
    private PersonSchemeService personSchemeService;

    @Nested
    @DisplayName("GET /pump-operators/{id}")
    class GetPumpOperatorDetails {

        @Test
        @DisplayName("returns 200 with pump operator details")
        void returns200() throws Exception {
            PumpOperatorDetailsDTO dto = PumpOperatorDetailsDTO.builder().id(1L).build();
            when(publicPumpOperatorService.getPumpOperatorDetails(eq("mp"), eq(1L))).thenReturn(dto);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/1").param("tenantCode", "mp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1));
        }

        @Test
        @DisplayName("returns 404 when operator not found")
        void returns404() throws Exception {
            when(publicPumpOperatorService.getPumpOperatorDetails(anyString(), anyLong()))
                    .thenThrow(new ResponseStatusException(NOT_FOUND, "not found"));

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/99").param("tenantCode", "mp"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /pump-operators/{id}/reading-compliance")
    class GetReadingCompliance {

        @Test
        @DisplayName("returns 200 with compliance data")
        void returns200() throws Exception {
            PumpOperatorReadingComplianceDTO dto = PumpOperatorReadingComplianceDTO.builder().build();
            when(publicPumpOperatorService.getReadingCompliance(eq("mp"), eq(1L))).thenReturn(dto);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/1/reading-compliance")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /pump-operators/{id}/details-with-compliance")
    class GetDetailsWithCompliance {

        @Test
        @DisplayName("returns 200 with combined details")
        void returns200() throws Exception {
            PumpOperatorDetailsWithComplianceDTO dto = PumpOperatorDetailsWithComplianceDTO.builder()
                    .details(PumpOperatorDetailsDTO.builder().id(1L).build())
                    .readingCompliance(PumpOperatorReadingComplianceDTO.builder().build())
                    .build();
            when(publicPumpOperatorService.getPumpOperatorDetailsWithCompliance(eq("mp"), eq(1L))).thenReturn(dto);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/1/details-with-compliance")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.details.id").value(1));
        }
    }

    @Nested
    @DisplayName("GET /pump-operators/reading-compliance")
    class ListReadingCompliance {

        @Test
        @DisplayName("returns 200 with paginated compliance rows")
        void returns200() throws Exception {
            PageResponseDTO<PumpOperatorReadingComplianceRowDTO> page =
                    PageResponseDTO.<PumpOperatorReadingComplianceRowDTO>builder()
                            .content(List.of()).totalElements(0L).totalPages(0).number(0).size(20).build();
            when(publicPumpOperatorService.listReadingCompliance(anyString(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/reading-compliance")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /pump-operators/by-scheme/reading-compliance")
    class ListBySchemeWithCompliance {

        @Test
        @DisplayName("returns 200 with scheme compliance rows")
        void returns200() throws Exception {
            PageResponseDTO<PumpOperatorSchemeComplianceRowDTO> page =
                    PageResponseDTO.<PumpOperatorSchemeComplianceRowDTO>builder()
                            .content(List.of()).totalElements(0L).totalPages(0).number(0).size(20).build();
            when(publicPumpOperatorService.listPumpOperatorsBySchemeWithCompliance(
                    anyString(), anyLong(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance")
                            .param("tenantCode", "mp").param("schemeId", "5"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /pump-operators/by-scheme")
    class ListByScheme {

        @Test
        @DisplayName("returns 200 with pump operators grouped by scheme")
        void returns200() throws Exception {
            when(publicPumpOperatorService.listPumpOperatorsByScheme(anyString(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 400 when page is negative")
        void returns400ForNegativePage() throws Exception {
            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme")
                            .param("tenantCode", "mp").param("page", "-1").param("size", "10"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when size exceeds 500")
        void returns400ForLargeSize() throws Exception {
            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme")
                            .param("tenantCode", "mp").param("page", "0").param("size", "501"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /pump-operators/{id}/readings")
    class ListPumpOperatorReadings {

        @Test
        @DisplayName("returns 200 with paginated readings")
        void returns200() throws Exception {
            PageResponseDTO<PumpOperatorReadingDetailDTO> page =
                    PageResponseDTO.<PumpOperatorReadingDetailDTO>builder()
                            .content(List.of()).totalElements(0L).totalPages(0).number(0).size(20).build();
            when(personSchemeService.listPumpOperatorReadings(anyString(), anyLong(), any(),
                    anyString(), anyString(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/1/readings")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /person/{id}/schemes/count")
    class CountSchemesByPerson {

        @Test
        @DisplayName("returns 200 with count")
        void returns200() throws Exception {
            when(personSchemeService.countSchemesByPerson(anyString(), anyLong(), any())).thenReturn(3L);

            mockMvc.perform(get("/api/v1/pumpoperator/person/10/schemes/count")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.schemeCount").value(3));
        }
    }

    @Nested
    @DisplayName("GET /person/{id}/schemes")
    class ListSchemesByPerson {

        @Test
        @DisplayName("returns 200 with paginated schemes")
        void returns200() throws Exception {
            PageResponseDTO<PersonSchemeDetailsDTO> page =
                    PageResponseDTO.<PersonSchemeDetailsDTO>builder()
                            .content(List.of()).totalElements(0L).totalPages(0).number(0).size(20).build();
            when(personSchemeService.listSchemesByPerson(anyString(), anyLong(), any(),
                    anyString(), anyString(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/pumpoperator/person/10/schemes")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /person/{id}/pump-operators")
    class ListPumpOperatorsByPerson {

        @Test
        @DisplayName("returns 200 with paginated pump operators")
        void returns200() throws Exception {
            PageResponseDTO<PumpOperatorSummaryWithMetricsDTO> page =
                    PageResponseDTO.<PumpOperatorSummaryWithMetricsDTO>builder()
                            .content(List.of()).totalElements(0L).totalPages(0).number(0).size(20).build();
            when(personSchemeService.listPumpOperatorsByPerson(
                    anyString(), anyLong(), any(), any(), any(), any(), any(),
                    anyString(), anyString(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/pumpoperator/person/10/pump-operators")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 400 when start_date is after end_date")
        void returns400ForInvalidDateRange() throws Exception {
            mockMvc.perform(get("/api/v1/pumpoperator/person/10/pump-operators")
                            .param("tenantCode", "mp")
                            .param("start_date", "2024-06-01")
                            .param("end_date", "2024-05-01"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /schemes/{id}/details")
    class GetSchemeDetails {

        @Test
        @DisplayName("returns 200 with scheme details")
        void returns200() throws Exception {
            SchemeDetailsWithReportingDTO dto = SchemeDetailsWithReportingDTO.builder().build();
            when(personSchemeService.getSchemeDetails(anyString(), anyLong())).thenReturn(dto);

            mockMvc.perform(get("/api/v1/pumpoperator/schemes/5/details")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 404 when scheme not found")
        void returns404WhenNull() throws Exception {
            when(personSchemeService.getSchemeDetails(anyString(), anyLong())).thenReturn(null);

            mockMvc.perform(get("/api/v1/pumpoperator/schemes/99/details")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /schemes/{id}/reading-submissions")
    class ListSchemeReadings {

        @Test
        @DisplayName("returns 200 with paginated submissions")
        void returns200() throws Exception {
            PageResponseDTO<SchemeReadingSubmissionDTO> page =
                    PageResponseDTO.<SchemeReadingSubmissionDTO>builder()
                            .content(List.of()).totalElements(0L).totalPages(0).number(0).size(20).build();
            when(personSchemeService.listSchemeReadings(anyString(), anyLong(), anyInt(), anyInt()))
                    .thenReturn(page);

            mockMvc.perform(get("/api/v1/pumpoperator/schemes/5/reading-submissions")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }
    }
}
