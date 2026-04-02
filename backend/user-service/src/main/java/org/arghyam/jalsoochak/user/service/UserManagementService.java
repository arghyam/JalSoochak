package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.ChangePasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.InviteRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateProfileRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.AdminUserResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.springframework.security.core.Authentication;

public interface UserManagementService {

    void inviteUser(InviteRequestDTO request, Authentication caller);

    AdminUserResponseDTO getMe(String keycloakId);

    AdminUserResponseDTO updateMe(String keycloakId, UpdateProfileRequestDTO request);

    void changePassword(String keycloakId, ChangePasswordRequestDTO request);

    PageResponseDTO<AdminUserResponseDTO> listSuperUsers(AdminUserStatus status, int page, int limit);

    PageResponseDTO<AdminUserResponseDTO> listStateAdmins(String tenantCode, AdminUserStatus status, String name, Authentication caller, int page, int limit);

    AdminUserResponseDTO getUserById(Long id, Authentication caller);

    AdminUserResponseDTO updateUserById(Long id, Authentication caller, UpdateProfileRequestDTO request);

    void deactivateUser(Long id, Authentication caller);

    void activateUser(Long id, Authentication caller);

    void reinviteUser(Long id, Authentication caller);
}
