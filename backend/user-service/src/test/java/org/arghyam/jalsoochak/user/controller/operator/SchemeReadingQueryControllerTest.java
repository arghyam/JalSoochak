package org.arghyam.jalsoochak.user.controller.operator;

import org.arghyam.jalsoochak.user.config.PumpOperatorAccessGuard;
import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SchemeReadingQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("SchemeReadingQueryController Tests")
class SchemeReadingQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppProperties appProperties;

    @MockBean
    private PersonSchemeService personSchemeService;

    @MockBean
    private PumpOperatorAccessGuard accessGuard;

    @BeforeEach
    void resolveScope() {
        when(accessGuard.resolve(any(), any()))
                .thenReturn(new PumpOperatorAccessGuard.CallerScope("MP", "tenant_mp", true, null));
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
         * Every paginated endpoint on this controller.
         */
        static Stream<String> paginatedEndpoints() {
            return Stream.of(
                    "/api/v1/pumpoperator/schemes/5/reading-submissions"
            );
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
        @MethodSource("paginatedEndpoints")
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
        @MethodSource("paginatedEndpoints")
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
