package org.arghyam.jalsoochak.user.controller.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.dto.response.AdminUserResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.exceptions.InvalidCredentialsException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.service.UserManagementService;
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

/**
 * Tests for {@link AccountController} covering happy paths, error responses, and validation.
 * Security filters are disabled; for endpoints that resolve Authentication via
 * request.getUserPrincipal(), a JwtAuthenticationToken is injected using mockJwt().
 */
@WebMvcTest(AccountController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AccountController Tests")
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppProperties appProperties;

    @MockBean
    private UserManagementService userManagementService;

    /**
     * Sets a JwtAuthenticationToken as the request principal so that
     * SecurityUtils.getKeycloakId(authentication) resolves correctly
     * when Spring MVC filters are disabled.
     */
    private static RequestPostProcessor mockJwt() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("kc-uuid")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, List.of());
        return request -> {
            request.setUserPrincipal(auth);
            return request;
        };
    }

    // ── /me GET ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/users/me")
    class GetMeTests {

        @Test
        @DisplayName("Should return 200 with profile for authenticated user")
        void getMe_success_returns200() throws Exception {
            AdminUserResponseDTO dto = AdminUserResponseDTO.builder()
                    .id(1L).email("user@example.com").role("SUPER_USER").status(AdminUserStatus.ACTIVE.name()).build();

            when(userManagementService.getMe(anyString())).thenReturn(dto);

            mockMvc.perform(get("/api/v1/users/me").with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.email").value("user@example.com"));
        }

        @Test
        @DisplayName("Should return 404 when authenticated user not in DB")
        void getMe_notFound_returns404() throws Exception {
            when(userManagementService.getMe(anyString()))
                    .thenThrow(new ResourceNotFoundException("User not found"));

            mockMvc.perform(get("/api/v1/users/me").with(mockJwt()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // ── /me PATCH ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/v1/users/me")
    class UpdateMeTests {

        @Test
        @DisplayName("Should return 200 after updating profile")
        void updateMe_success_returns200() throws Exception {
            AdminUserResponseDTO dto = AdminUserResponseDTO.builder()
                    .id(1L).email("user@example.com").firstName("Updated").build();

            when(userManagementService.updateMe(anyString(), any())).thenReturn(dto);

            String payload = """
                    {
                      "firstName": "Updated",
                      "lastName": "User"
                    }
                    """;

            mockMvc.perform(patch("/api/v1/users/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.firstName").value("Updated"));
        }
    }

    // ── /me/password PATCH ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/v1/users/me/password")
    class ChangePasswordTests {

        @Test
        @DisplayName("Should return 200 on successful password change")
        void changePassword_success_returns200() throws Exception {
            doNothing().when(userManagementService).changePassword(anyString(), any());

            String payload = """
                    {
                      "currentPassword": "OldPass@123",
                      "newPassword": "NewPass@123"
                    }
                    """;

            mockMvc.perform(patch("/api/v1/users/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }

        @Test
        @DisplayName("Should return 401 when current password is wrong")
        void changePassword_wrongPassword_returns401() throws Exception {
            doThrow(new InvalidCredentialsException("Current password is incorrect"))
                    .when(userManagementService).changePassword(anyString(), any());

            String payload = """
                    {
                      "currentPassword": "WrongPass@123",
                      "newPassword": "NewPass@123"
                    }
                    """;

            mockMvc.perform(patch("/api/v1/users/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(mockJwt()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("Missing current password should return 400")
        void changePassword_missingCurrentPassword_returns400() throws Exception {
            mockMvc.perform(patch("/api/v1/users/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"newPassword\":\"NewPass@123\"}")
                            .with(mockJwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }
}
