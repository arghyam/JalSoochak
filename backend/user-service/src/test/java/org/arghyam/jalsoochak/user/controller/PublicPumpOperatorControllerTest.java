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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Stream;

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
            PumpOperatorDetailsDTO dto = PumpOperatorDetailsDTO.builder()
                    .id(1L)
                    .stateSchemeId("STATE-5")
                    .centerSchemeId("CENTER-5")
                    .build();
            when(publicPumpOperatorService.getPumpOperatorDetails(eq("mp"), eq(1L), eq(5L), any(), any())).thenReturn(dto);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/1")
                            .param("tenantCode", "mp")
                            .param("schemeId", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.stateSchemeId").value("STATE-5"))
                    .andExpect(jsonPath("$.data.centerSchemeId").value("CENTER-5"));
        }

        @Test
        @DisplayName("returns 200 when schemeId is omitted")
        void returns200WithoutSchemeId() throws Exception {
            PumpOperatorDetailsDTO dto = PumpOperatorDetailsDTO.builder().id(1L).build();
            when(publicPumpOperatorService.getPumpOperatorDetails(eq("mp"), eq(1L), eq(null), any(), any())).thenReturn(dto);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/1")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1));
        }

        @Test
        @DisplayName("returns 404 when operator not found")
        void returns404() throws Exception {
            when(publicPumpOperatorService.getPumpOperatorDetails(anyString(), anyLong(), any(), any(), any()))
                    .thenThrow(new ResponseStatusException(NOT_FOUND, "not found"));

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/99")
                            .param("tenantCode", "mp")
                            .param("schemeId", "5"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 400 when startDate is after endDate")
        void returns400WhenStartDateAfterEndDate() throws Exception {
            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/1")
                            .param("tenantCode", "mp")
                            .param("schemeId", "5")
                            .param("startDate", "2024-06-02")
                            .param("endDate", "2024-06-01"))
                    .andExpect(status().isBadRequest());
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
                    anyString(), anyLong(), any(), any(), any(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance")
                            .param("tenantCode", "mp")
                            .param("schemeId", "5")
                            .param("pumpOperatorId", "9"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 200 when pumpOperatorId is missing")
        void returns200WhenPumpOperatorIdMissing() throws Exception {
            PageResponseDTO<PumpOperatorSchemeComplianceRowDTO> page =
                    PageResponseDTO.<PumpOperatorSchemeComplianceRowDTO>builder()
                            .content(List.of()).totalElements(0L).totalPages(0).number(0).size(20).build();
            when(publicPumpOperatorService.listPumpOperatorsBySchemeWithCompliance(
                    anyString(), anyLong(), any(), any(), any(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance")
                            .param("tenantCode", "mp")
                            .param("schemeId", "5"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 400 when startDate is after endDate")
        void returns400WhenStartDateAfterEndDate() throws Exception {
            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance")
                            .param("tenantCode", "mp")
                            .param("schemeId", "5")
                            .param("pumpOperatorId", "9")
                            .param("startDate", "2024-06-02")
                            .param("endDate", "2024-06-01"))
                    .andExpect(status().isBadRequest());
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
        @DisplayName("returns 400 when size exceeds the shared 100 maximum")
        void returns400ForLargeSize() throws Exception {
            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme")
                            .param("tenantCode", "mp").param("page", "0").param("size", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("size must be between 1 and 100"));
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

    @Nested
    @DisplayName("Pagination boundary validation")
    class PaginationValidation {

        /**
         * Every paginated endpoint on this controller. {@code schemeId} is always supplied by the
         * tests so the by-scheme endpoint never fails argument resolution instead of validation.
         */
        static Stream<String> paginatedEndpoints() {
            return Stream.of(
                    "/api/v1/pumpoperator/pump-operators/reading-compliance",
                    "/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance",
                    "/api/v1/pumpoperator/pump-operators/1/readings",
                    "/api/v1/pumpoperator/person/10/schemes",
                    "/api/v1/pumpoperator/person/10/pump-operators",
                    "/api/v1/pumpoperator/schemes/5/reading-submissions"
            );
        }

        /**
         * The same endpoints plus {@code /pump-operators/by-scheme}, which shares the ceiling but
         * checks it by hand — it only paginates when asked to, so the bounds cannot be annotated.
         * Its rejection carries no {@code fieldErrors} entry, so it joins only the assertions on
         * the maximum, which turn on the status.
         */
        static Stream<String> endpointsSharingTheMaximumPageSize() {
            return Stream.concat(
                    paginatedEndpoints(),
                    Stream.of("/api/v1/pumpoperator/pump-operators/by-scheme"));
        }

        @ParameterizedTest(name = "{0} rejects page=-1")
        @MethodSource("paginatedEndpoints")
        @DisplayName("returns 400 when page is negative")
        void returns400ForNegativePage(String path) throws Exception {
            mockMvc.perform(get(path)
                            .param("tenantCode", "mp")
                            .param("schemeId", "5")
                            .param("page", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
        }

        @ParameterizedTest(name = "{0} rejects size=0")
        @MethodSource("paginatedEndpoints")
        @DisplayName("returns 400 when size is zero")
        void returns400ForZeroSize(String path) throws Exception {
            mockMvc.perform(get(path)
                            .param("tenantCode", "mp")
                            .param("schemeId", "5")
                            .param("size", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        }

        @ParameterizedTest(name = "{0} rejects size=-1")
        @MethodSource("paginatedEndpoints")
        @DisplayName("returns 400 when size is negative")
        void returns400ForNegativeSize(String path) throws Exception {
            mockMvc.perform(get(path)
                            .param("tenantCode", "mp")
                            .param("schemeId", "5")
                            .param("size", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @ParameterizedTest(name = "{0} rejects size=101")
        @MethodSource("endpointsSharingTheMaximumPageSize")
        @DisplayName("returns 400 when size exceeds the 100 maximum")
        void returns400ForSizeAboveMax(String path) throws Exception {
            mockMvc.perform(get(path)
                            .param("tenantCode", "mp")
                            .param("schemeId", "5")
                            .param("size", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @ParameterizedTest(name = "{0} accepts size=100")
        @MethodSource("endpointsSharingTheMaximumPageSize")
        @DisplayName("accepts the boundary values page=0 and size=100")
        void accepts200ForBoundaryValues(String path) throws Exception {
            mockMvc.perform(get(path)
                            .param("tenantCode", "mp")
                            .param("schemeId", "5")
                            .param("page", "0")
                            .param("size", "100"))
                    .andExpect(status().isOk());
        }
    }
}
