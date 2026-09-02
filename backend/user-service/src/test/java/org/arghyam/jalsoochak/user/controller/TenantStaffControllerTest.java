package org.arghyam.jalsoochak.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateStaffRoleRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.WelcomeMessageRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.RoleCountDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.WelcomeMessageResponseDTO;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.service.TenantStaffService;
import org.arghyam.jalsoochak.user.service.WelcomeMessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantStaffController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TenantStaffController Tests")
class TenantStaffControllerTest {

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

    private static RequestPostProcessor mockJwt(String subject, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(authorities)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toList());
        return request -> {
            request.setUserPrincipal(token);
            return request;
        };
    }

    @Nested
    @DisplayName("GET /api/v1/tenant/user/staff")
    class ListStaff {

        @Test
        @DisplayName("returns 200 with staff list")
        void returns200() throws Exception {
            PageResponseDTO<TenantStaffResponseDTO> page = PageResponseDTO.<TenantStaffResponseDTO>builder()
                    .content(List.of(TenantStaffResponseDTO.builder().id(1L).role("SECTION_OFFICER").build()))
                    .totalElements(1L).totalPages(1).number(0).size(20).build();

            when(tenantStaffService.listStaff(eq("mp"), eq(0), eq(20),
                    anyString(), anyString(), any(), any(), any()))
                    .thenReturn(page);

            mockMvc.perform(get("/api/v1/tenant/user/staff")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(1));
        }

        @Test
        @DisplayName("returns 400 when page is negative")
        void returns400ForNegativePage() throws Exception {
            mockMvc.perform(get("/api/v1/tenant/user/staff")
                            .param("tenantCode", "mp")
                            .param("page", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
        }

        @Test
        @DisplayName("returns 400 when limit is zero")
        void returns400ForZeroLimit() throws Exception {
            mockMvc.perform(get("/api/v1/tenant/user/staff")
                            .param("tenantCode", "mp")
                            .param("limit", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
        }

        @Test
        @DisplayName("returns 400 when limit exceeds the 100 maximum")
        void returns400ForLimitAboveMax() throws Exception {
            mockMvc.perform(get("/api/v1/tenant/user/staff")
                            .param("tenantCode", "mp")
                            .param("limit", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/tenant/user/staff/{id}/role")
    class UpdateStaffRole {

        @Test
        @DisplayName("returns 200 with updated staff on valid request")
        void returns200() throws Exception {
            TenantStaffResponseDTO updated = TenantStaffResponseDTO.builder()
                    .id(10L).role("SUB_DIVISIONAL_OFFICER").build();
            when(tenantStaffService.updateStaffRole(eq(10L), any(), any())).thenReturn(updated);

            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            mockMvc.perform(put("/api/v1/tenant/user/staff/10/role")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value("SUB_DIVISIONAL_OFFICER"));
        }

        @Test
        @DisplayName("returns 400 for invalid path variable (non-positive)")
        void returns400ForNonPositiveId() throws Exception {
            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            mockMvc.perform(put("/api/v1/tenant/user/staff/0/role")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 403 on ForbiddenAccessException")
        void returns403OnForbidden() throws Exception {
            when(tenantStaffService.updateStaffRole(any(), any(), any()))
                    .thenThrow(new ForbiddenAccessException("forbidden"));

            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            mockMvc.perform(put("/api/v1/tenant/user/staff/10/role")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 404 on ResourceNotFoundException")
        void returns404OnNotFound() throws Exception {
            when(tenantStaffService.updateStaffRole(any(), any(), any()))
                    .thenThrow(new ResourceNotFoundException("not found"));

            UpdateStaffRoleRequestDTO req = new UpdateStaffRoleRequestDTO("mp", "SUB_DIVISIONAL_OFFICER");

            mockMvc.perform(put("/api/v1/tenant/user/staff/10/role")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/tenant/user/staff/counts/by-role")
    class CountStaffByRole {

        @Test
        @DisplayName("returns 200 with role counts")
        void returns200() throws Exception {
            when(tenantStaffService.countStaffByRole(eq("mp"), any(), any()))
                    .thenReturn(List.of(new RoleCountDTO("SECTION_OFFICER", 3)));

            mockMvc.perform(get("/api/v1/tenant/user/staff/counts/by-role")
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].role").value("SECTION_OFFICER"))
                    .andExpect(jsonPath("$.data[0].count").value(3));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/tenant/user/staff/{id}/deactivate")
    class DeactivateStaff {

        @Test
        @DisplayName("returns 200 on successful deactivation")
        void returns200() throws Exception {
            // tenantStaffService.deactivateStaff is void — Mockito does nothing by default
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Staff user deactivated successfully"));
        }

        @Test
        @DisplayName("returns 400 for non-positive id")
        void returns400ForNonPositiveId() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/0/deactivate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when tenantCode is blank")
        void returns400ForBlankTenantCode() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "   "))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when tenantCode is missing")
        void returns400ForMissingTenantCode() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 403 on ForbiddenAccessException")
        void returns403OnForbidden() throws Exception {
            doThrow(new ForbiddenAccessException("forbidden"))
                    .when(tenantStaffService).deactivateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 400 on BadRequestException (already deactivated)")
        void returns400OnAlreadyDeactivated() throws Exception {
            doThrow(new BadRequestException("already deactivated"))
                    .when(tenantStaffService).deactivateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 404 on ResourceNotFoundException")
        void returns404OnNotFound() throws Exception {
            doThrow(new ResourceNotFoundException("not found"))
                    .when(tenantStaffService).deactivateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/deactivate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/tenant/user/staff/{id}/activate")
    class ActivateStaff {

        @Test
        @DisplayName("returns 200 on successful activation")
        void returns200() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Staff user activated successfully"));
        }

        @Test
        @DisplayName("returns 400 for non-positive id")
        void returns400ForNonPositiveId() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/0/activate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when tenantCode is blank")
        void returns400ForBlankTenantCode() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "   "))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when tenantCode is missing")
        void returns400ForMissingTenantCode() throws Exception {
            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 403 on ForbiddenAccessException")
        void returns403OnForbidden() throws Exception {
            doThrow(new ForbiddenAccessException("forbidden"))
                    .when(tenantStaffService).activateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 400 on BadRequestException (already active)")
        void returns400OnAlreadyActive() throws Exception {
            doThrow(new BadRequestException("already active"))
                    .when(tenantStaffService).activateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 404 on ResourceNotFoundException")
        void returns404OnNotFound() throws Exception {
            doThrow(new ResourceNotFoundException("not found"))
                    .when(tenantStaffService).activateStaff(any(), any(), any());

            mockMvc.perform(post("/api/v1/tenant/user/staff/10/activate")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/tenant/user/welcome")
    class SendWelcomeMessages {

        @Test
        @DisplayName("returns 200 when welcome messages are queued")
        void returns200() throws Exception {
            WelcomeMessageResponseDTO resp = WelcomeMessageResponseDTO.builder()
                    .totalPhones(5).batches(1).batchSize(1000).publishedEvents(1)
                    .message("Queued welcome messages for 5 users").build();

            when(welcomeMessageService.sendWelcomeMessages(anyString(), any(), any())).thenReturn(resp);

            WelcomeMessageRequestDTO req = new WelcomeMessageRequestDTO();
            req.setType("welcome_template");
            req.setRoles(List.of("SECTION_OFFICER"));

            mockMvc.perform(post("/api/v1/tenant/user/welcome")
                            .with(mockJwt("kc-uuid", "ROLE_STATE_ADMIN"))
                            .param("tenantCode", "mp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalPhones").value(5));
        }
    }
}
