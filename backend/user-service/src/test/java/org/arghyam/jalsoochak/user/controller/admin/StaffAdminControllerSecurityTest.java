package org.arghyam.jalsoochak.user.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.user.config.JwtAuthConverter;
import org.arghyam.jalsoochak.user.config.SecurityConfig;
import org.arghyam.jalsoochak.user.config.UserSecurityEvaluator;
import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.dto.request.UpdateStaffRoleRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.WelcomeMessageRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.ReportResponseDTO;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.service.StaffReportService;
import org.arghyam.jalsoochak.user.service.TenantStaffService;
import org.arghyam.jalsoochak.user.service.WelcomeMessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security boundary tests for StaffAdminController.
 * Filters are enabled (no addFilters = false) to exercise the real security chain.
 *
 * <p>The {@link UserSecurityEvaluator} (bean name {@code userSecurity}) is mocked
 * here; its internal logic is exercised separately in
 * {@code UserSecurityEvaluatorTest}.
 */
@WebMvcTest(StaffAdminController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@DisplayName("StaffAdminController Security Tests")
class StaffAdminControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AppProperties appProperties;

    @MockBean
    private TenantStaffService tenantStaffService;

    @MockBean
    private WelcomeMessageService welcomeMessageService;

    @MockBean
    private StaffReportService staffReportService;

    @MockBean(name = "userSecurity")
    private UserSecurityEvaluator userSecurity;

    @Nested
    @DisplayName("PUT /api/v1/tenant/user/staff/{id}/role — requires STATE_ADMIN")
    class UpdateStaffRoleSecurity {

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void unauthenticated_returns401() throws Exception {
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            mockMvc.perform(put("/api/v1/tenant/user/staff/10/role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnauthorized());

            verify(tenantStaffService, never()).updateStaffRole(any(), any(), any());
        }

        @Test
        @DisplayName("returns 403 when authenticated but lacks STATE_ADMIN role")
        void wrongRole_returns403() throws Exception {
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            mockMvc.perform(put("/api/v1/tenant/user/staff/10/role")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SECTION_OFFICER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());

            verify(tenantStaffService, never()).updateStaffRole(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/tenant/user/welcome — requires SUPER_USER or STATE_ADMIN")
    class SendWelcomeMessagesSecurity {

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void unauthenticated_returns401() throws Exception {
            WelcomeMessageRequestDTO req = new WelcomeMessageRequestDTO();
            req.setType("welcome_template");
            req.setRoles(List.of("SECTION_OFFICER"));

            mockMvc.perform(post("/api/v1/tenant/user/welcome")
                            .param("tenantCode", "mp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnauthorized());

            verify(welcomeMessageService, never()).sendWelcomeMessages(any(), any(), any());
        }

        @Test
        @DisplayName("returns 403 when authenticated but lacks SUPER_USER or STATE_ADMIN role")
        void wrongRole_returns403() throws Exception {
            WelcomeMessageRequestDTO req = new WelcomeMessageRequestDTO();
            req.setType("welcome_template");
            req.setRoles(List.of("SECTION_OFFICER"));

            mockMvc.perform(post("/api/v1/tenant/user/welcome")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SECTION_OFFICER")))
                            .param("tenantCode", "mp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());

            verify(welcomeMessageService, never()).sendWelcomeMessages(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/tenant/user/staff/{id}/deactivate — requires SUPER_USER or STATE_ADMIN")
    class DeactivateStaffSecurity {

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isUnauthorized());

            verify(tenantStaffService, never()).deactivateStaff(any(), any(), any());
        }

        @Test
        @DisplayName("returns 403 when authenticated but lacks required role")
        void wrongRole_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SECTION_OFFICER")))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isForbidden());

            verify(tenantStaffService, never()).deactivateStaff(any(), any(), any());
        }

        @Test
        @DisplayName("returns 200 when authenticated as STATE_ADMIN")
        void stateAdmin_returns200() throws Exception {
            doNothing().when(tenantStaffService).deactivateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 200 when authenticated as SUPER_USER")
        void superUser_returns200() throws Exception {
            doNothing().when(tenantStaffService).deactivateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/tenant/user/staff/{id}/activate — requires SUPER_USER or STATE_ADMIN")
    class ActivateStaffSecurity {

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isUnauthorized());

            verify(tenantStaffService, never()).activateStaff(any(), any(), any());
        }

        @Test
        @DisplayName("returns 403 when authenticated but lacks required role")
        void wrongRole_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SECTION_OFFICER")))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isForbidden());

            verify(tenantStaffService, never()).activateStaff(any(), any(), any());
        }

        @Test
        @DisplayName("returns 200 when authenticated as STATE_ADMIN")
        void stateAdmin_returns200() throws Exception {
            doNothing().when(tenantStaffService).activateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 200 when authenticated as SUPER_USER")
        void superUser_returns200() throws Exception {
            doNothing().when(tenantStaffService).activateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }
    }

    /**
     * The staff directory returns names, emails and phone numbers for every officer in a tenant.
     * It was {@code permitAll} with a caller-supplied {@code tenantCode}, so anyone could read any
     * tenant's contact list without a token — the same defect reported for the pump operator API.
     */
    @Nested
    @DisplayName("GET /api/v1/tenant/user/staff — tenant-scoped admin only")
    class ListStaffSecurity {

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/tenant/user/staff")
                            .param("tenantCode", "mp")
                            .param("role", "SECTION_OFFICER"))
                    .andExpect(status().isUnauthorized());

            verify(tenantStaffService, never())
                    .listStaff(anyString(), anyInt(), anyInt(), anyString(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 403 for an authenticated non-admin")
        void wrongRole_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/tenant/user/staff")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SECTION_OFFICER")))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isForbidden());

            verify(tenantStaffService, never())
                    .listStaff(anyString(), anyInt(), anyInt(), anyString(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 403 when a STATE_ADMIN names another tenant")
        void crossTenant_returns403() throws Exception {
            when(userSecurity.canAccessTenant(eq("tr"), any(Authentication.class))).thenReturn(false);

            mockMvc.perform(get("/api/v1/tenant/user/staff")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                            .param("tenantCode", "tr"))
                    .andExpect(status().isForbidden());

            verify(tenantStaffService, never())
                    .listStaff(anyString(), anyInt(), anyInt(), anyString(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 200 for a STATE_ADMIN reading their own tenant")
        void ownTenant_returns200() throws Exception {
            when(userSecurity.canAccessTenant(eq("mp"), any(Authentication.class))).thenReturn(true);

            mockMvc.perform(get("/api/v1/tenant/user/staff")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/tenant/user/staff/counts/by-role — tenant-scoped admin only")
    class CountStaffByRoleSecurity {

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/tenant/user/staff/counts/by-role")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isUnauthorized());

            verify(tenantStaffService, never()).countStaffByRole(anyString(), any(), any());
        }

        @Test
        @DisplayName("returns 403 when a STATE_ADMIN names another tenant")
        void crossTenant_returns403() throws Exception {
            when(userSecurity.canAccessTenant(eq("tr"), any(Authentication.class))).thenReturn(false);

            mockMvc.perform(get("/api/v1/tenant/user/staff/counts/by-role")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                            .param("tenantCode", "tr"))
                    .andExpect(status().isForbidden());

            verify(tenantStaffService, never()).countStaffByRole(anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/tenant/user/staff/reports — requires STATE_ADMIN of the requested tenant")
    class GenerateStaffReportSecurity {

        private static final String URL = "/api/v1/tenant/user/staff/reports";

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
        @DisplayName("STATE_ADMIN of the matching tenant → 200")
        void stateAdmin_sameTenant_returns200() throws Exception {
            when(userSecurity.canAccessTenant(eq("mp"), any(Authentication.class))).thenReturn(true);
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

        @Test
        @DisplayName("STATE_ADMIN requesting another tenant → 403, service not invoked")
        void stateAdmin_crossTenant_returns403() throws Exception {
            when(userSecurity.canAccessTenant(eq("tr"), any(Authentication.class))).thenReturn(false);

            mockMvc.perform(post(URL)
                            .param("tenantCode", "tr")
                            .param("format", "CSV")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());

            verify(staffReportService, never()).generate(any(), any(), any(), any());
        }

        @Test
        @DisplayName("evaluator is consulted with the exact request tenantCode")
        void evaluatorReceivesRequestTenantCode() throws Exception {
            when(userSecurity.canAccessTenant(anyString(), any(Authentication.class))).thenReturn(true);
            when(staffReportService.generate(any(), any(), any(), any())).thenReturn(okResponse());

            mockMvc.perform(post(URL)
                            .param("tenantCode", "MP")
                            .param("format", "CSV")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            verify(userSecurity).canAccessTenant(eq("MP"), any(Authentication.class));
        }
    }
}
