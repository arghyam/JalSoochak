package org.arghyam.jalsoochak.user.controller;

import org.arghyam.jalsoochak.user.config.JwtAuthConverter;
import org.arghyam.jalsoochak.user.config.SecurityConfig;
import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.dto.response.ReportResponseDTO;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.service.StaffReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security boundary tests for {@link StaffReportController}.
 * Filters are enabled so the real Spring Security chain decides on each request.
 */
@WebMvcTest(StaffReportController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@DisplayName("StaffReportController Security")
class StaffReportControllerSecurityTest {

    private static final String URL = "/api/v1/tenant/user/staff/reports";

    @Autowired private MockMvc mockMvc;
    @MockBean private AppProperties appProperties;
    @MockBean private StaffReportService staffReportService;

    private static ReportResponseDTO okResponse() {
        return ReportResponseDTO.builder()
                .reportId(UUID.randomUUID()).format("CSV")
                .generatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .dataVersion(1L)
                .downloadUrl("https://example/x").urlExpiresAt(OffsetDateTime.now(ZoneOffset.UTC))
                .cached(false).build();
    }

    @Test
    @DisplayName("unauthenticated → 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(URL)
                        .param("tenantCode", "mp")
                        .param("format", "CSV")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verify(staffReportService, never()).generate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("authenticated without STATE_ADMIN → 403")
    void wrongRole_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .param("tenantCode", "mp")
                        .param("format", "CSV")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SECTION_OFFICER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(staffReportService, never()).generate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("SUPER_USER → 403 (endpoint is STATE_ADMIN-only)")
    void superUser_returns403() throws Exception {
        mockMvc.perform(post(URL)
                        .param("tenantCode", "mp")
                        .param("format", "CSV")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(staffReportService, never()).generate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("STATE_ADMIN → 200")
    void stateAdmin_returns200() throws Exception {
        when(staffReportService.generate(eq("mp"), eq(ReportFormat.CSV), any(), any()))
                .thenReturn(okResponse());

        mockMvc.perform(post(URL)
                        .param("tenantCode", "mp")
                        .param("format", "CSV")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
