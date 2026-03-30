package org.arghyam.jalsoochak.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.AdminUserResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.InsufficientActiveUsersException;
import org.arghyam.jalsoochak.user.exceptions.InvalidCredentialsException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.exceptions.UserAlreadyExistsException;
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
 * Tests for {@link UserController} covering happy paths, error responses, and validation.
 * Security filters are disabled; for endpoints that resolve Authentication via
 * request.getUserPrincipal(), a JwtAuthenticationToken is injected using mockJwt().
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("UserController Tests")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    // ── /invite ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/users/invite")
    class InviteTests {

        @Test
        @DisplayName("Should return 200 on valid invite request")
        void invite_success_returns200() throws Exception {
            doNothing().when(userManagementService).inviteUser(any(), any());

            String payload = """
                    {
                      "email": "new@example.com",
                      "role": "STATE_ADMIN",
                      "firstName": "John",
                      "lastName": "Doe",
                      "phoneNumber": "9112345678"
                    }
                    """;

            mockMvc.perform(post("/api/v1/users/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }

        @Test
        @DisplayName("Missing email and role should return 400")
        void invite_missingFields_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/users/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Invalid email format should return 400")
        void invite_invalidEmail_returns400() throws Exception {
            String payload = """
                    {
                      "email": "not-an-email",
                      "role": "SUPER_USER"
                    }
                    """;

            mockMvc.perform(post("/api/v1/users/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Should return 409 when email already exists")
        void invite_duplicateEmail_returns409() throws Exception {
            doThrow(new UserAlreadyExistsException("Email already registered"))
                    .when(userManagementService).inviteUser(any(), any());

            String payload = """
                    {
                      "email": "existing@example.com",
                      "role": "STATE_ADMIN",
                      "firstName": "John",
                      "lastName": "Doe",
                      "phoneNumber": "9112345678"
                    }
                    """;

            mockMvc.perform(post("/api/v1/users/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("Should return 403 when state admin invites cross-state")
        void invite_crossState_returns403() throws Exception {
            doThrow(new ForbiddenAccessException("State admin can only invite within their own state"))
                    .when(userManagementService).inviteUser(any(), any());

            String payload = """
                    {
                      "email": "other@example.com",
                      "role": "STATE_ADMIN",
                      "firstName": "Jane",
                      "lastName": "Smith",
                      "phoneNumber": "9198765432"
                    }
                    """;

            mockMvc.perform(post("/api/v1/users/invite")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
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

    // ── /super-users ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/users/super-users")
    class ListSuperUsersTests {

        @Test
        @DisplayName("Should return 200 with paginated super users when no status filter")
        void listSuperUsers_returns200() throws Exception {
            AdminUserResponseDTO dto = AdminUserResponseDTO.builder()
                    .id(1L).email("su@example.com").role("SUPER_USER").status(AdminUserStatus.ACTIVE.name()).build();
            PageResponseDTO<AdminUserResponseDTO> page = PageResponseDTO.of(List.of(dto), 1L, 0, 20);

            when(userManagementService.listSuperUsers(any(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/users/super-users")
                            .with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.content[0].email").value("su@example.com"))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        @DisplayName("Should return 200 filtered by ACTIVE status")
        void listSuperUsers_withStatusFilter_returns200() throws Exception {
            AdminUserResponseDTO dto = AdminUserResponseDTO.builder()
                    .id(1L).email("su@example.com").role("SUPER_USER").status(AdminUserStatus.ACTIVE.name()).build();
            PageResponseDTO<AdminUserResponseDTO> page = PageResponseDTO.of(List.of(dto), 1L, 0, 20);

            when(userManagementService.listSuperUsers(eq(AdminUserStatus.ACTIVE), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/users/super-users")
                            .param("status", "ACTIVE")
                            .with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"));
        }

        @Test
        @DisplayName("Should return 400 for invalid status value")
        void listSuperUsers_invalidStatus_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/users/super-users")
                            .param("status", "BADVALUE")
                            .with(mockJwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    // ── /state-admins ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/users/state-admins")
    class ListStateAdminsTests {

        @Test
        @DisplayName("Should return 200 with paginated state admins when no status filter")
        void listStateAdmins_returns200() throws Exception {
            AdminUserResponseDTO dto = AdminUserResponseDTO.builder()
                    .id(2L).email("admin@mp.com").role("STATE_ADMIN").tenantCode("MP").status(AdminUserStatus.ACTIVE.name()).build();
            PageResponseDTO<AdminUserResponseDTO> page = PageResponseDTO.of(List.of(dto), 1L, 0, 20);

            when(userManagementService.listStateAdmins(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/users/state-admins")
                            .param("tenantCode", "MP")
                            .with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.content[0].tenantCode").value("MP"));
        }

        @Test
        @DisplayName("Should return 200 filtered by INACTIVE status")
        void listStateAdmins_withStatusFilter_returns200() throws Exception {
            AdminUserResponseDTO dto = AdminUserResponseDTO.builder()
                    .id(2L).email("admin@mp.com").role("STATE_ADMIN").tenantCode("MP").status(AdminUserStatus.INACTIVE.name()).build();
            PageResponseDTO<AdminUserResponseDTO> page = PageResponseDTO.of(List.of(dto), 1L, 0, 20);

            when(userManagementService.listStateAdmins(any(), eq(AdminUserStatus.INACTIVE), any(), any(), anyInt(), anyInt())).thenReturn(page);

            mockMvc.perform(get("/api/v1/users/state-admins")
                            .param("status", "INACTIVE")
                            .with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.content[0].status").value("INACTIVE"));
        }

        @Test
        @DisplayName("Should return 400 for invalid status value")
        void listStateAdmins_invalidStatus_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/users/state-admins")
                            .param("status", "UNKNOWN")
                            .with(mockJwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    // ── /{id} GET ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/users/{id}")
    class GetUserByIdTests {

        @Test
        @DisplayName("Should return 200 with user data when user exists")
        void getUserById_returns200() throws Exception {
            AdminUserResponseDTO dto = AdminUserResponseDTO.builder()
                    .id(5L).email("admin@example.com").role("STATE_ADMIN").status(AdminUserStatus.ACTIVE.name()).build();

            when(userManagementService.getUserById(eq(5L), any())).thenReturn(dto);

            mockMvc.perform(get("/api/v1/users/5").with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.email").value("admin@example.com"));
        }

        @Test
        @DisplayName("Should return 404 when user does not exist")
        void getUserById_notFound_returns404() throws Exception {
            when(userManagementService.getUserById(anyLong(), any()))
                    .thenThrow(new ResourceNotFoundException("User not found"));

            mockMvc.perform(get("/api/v1/users/999").with(mockJwt()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("User not found"));
        }
    }

    // ── /{id} PATCH ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/v1/users/{id}")
    class UpdateUserByIdTests {

        @Test
        @DisplayName("Should return 200 after updating user")
        void updateUserById_success_returns200() throws Exception {
            AdminUserResponseDTO dto = AdminUserResponseDTO.builder()
                    .id(3L).email("admin@example.com").firstName("Updated").build();

            when(userManagementService.updateUserById(anyLong(), any(), any())).thenReturn(dto);

            String payload = """
                    {
                      "firstName": "Updated",
                      "lastName": "Admin"
                    }
                    """;

            mockMvc.perform(patch("/api/v1/users/3").with(mockJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.firstName").value("Updated"));
        }
    }

    // ── /{id}/deactivate ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/users/{id}/deactivate")
    class DeactivateTests {

        @Test
        @DisplayName("Should return 200 on successful deactivation")
        void deactivate_success_returns200() throws Exception {
            doNothing().when(userManagementService).deactivateUser(anyLong(), any());

            mockMvc.perform(put("/api/v1/users/4/deactivate").with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }

        @Test
        @DisplayName("Should return 409 when deactivating the last active user")
        void deactivate_lastUser_returns409() throws Exception {
            doThrow(new InsufficientActiveUsersException("At least one active super user must remain"))
                    .when(userManagementService).deactivateUser(anyLong(), any());

            mockMvc.perform(put("/api/v1/users/1/deactivate").with(mockJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }
    }

    // ── /{id}/activate ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/users/{id}/activate")
    class ActivateTests {

        @Test
        @DisplayName("Should return 200 on successful activation")
        void activate_success_returns200() throws Exception {
            doNothing().when(userManagementService).activateUser(anyLong(), any());

            mockMvc.perform(put("/api/v1/users/5/activate").with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }
    }

    // ── /{id}/reinvite ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/users/{id}/reinvite")
    class ReinviteTests {

        @Test
        @DisplayName("Should return 200 on successful reinvite")
        void reinvite_success_returns200() throws Exception {
            doNothing().when(userManagementService).reinviteUser(anyLong(), any());

            mockMvc.perform(post("/api/v1/users/7/reinvite").with(mockJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("Invitation resent successfully"));
        }

        @Test
        @DisplayName("Should return 400 when user has already activated their account")
        void reinvite_alreadyActivated_returns400() throws Exception {
            doThrow(new org.arghyam.jalsoochak.user.exceptions.BadRequestException("User has already activated their account"))
                    .when(userManagementService).reinviteUser(anyLong(), any());

            mockMvc.perform(post("/api/v1/users/8/reinvite").with(mockJwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Should return 404 when user does not exist")
        void reinvite_userNotFound_returns404() throws Exception {
            doThrow(new ResourceNotFoundException("User not found"))
                    .when(userManagementService).reinviteUser(anyLong(), any());

            mockMvc.perform(post("/api/v1/users/999/reinvite").with(mockJwt()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("Should return 403 when state admin reinvites across state boundary")
        void reinvite_crossState_returns403() throws Exception {
            doThrow(new ForbiddenAccessException("State admin can only reinvite within their own state"))
                    .when(userManagementService).reinviteUser(anyLong(), any());

            mockMvc.perform(post("/api/v1/users/10/reinvite").with(mockJwt()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // ── /super-users pagination validation ────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/users/super-users - pagination validation")
    class ListSuperUsersPaginationTests {

        @Test
        @DisplayName("Should return 400 when page is negative")
        void listSuperUsers_negativePage_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/users/super-users")
                            .param("page", "-1")
                            .with(mockJwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Should return 400 when size is zero")
        void listSuperUsers_zeroLimit_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/users/super-users")
                            .param("size", "0")
                            .with(mockJwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Should return 400 when size exceeds 100")
        void listSuperUsers_limitOver100_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/users/super-users")
                            .param("size", "101")
                            .with(mockJwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    // ── /state-admins pagination validation ───────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/users/state-admins - pagination validation")
    class ListStateAdminsPaginationTests {

        @Test
        @DisplayName("Should return 400 when page is negative")
        void listStateAdmins_negativePage_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/users/state-admins")
                            .param("page", "-1")
                            .with(mockJwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Should return 400 when size exceeds 100")
        void listStateAdmins_limitOver100_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/users/state-admins")
                            .param("size", "101")
                            .with(mockJwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }
}
