package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateStaffRoleRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.RoleCountDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface TenantStaffService {

    PageResponseDTO<TenantStaffResponseDTO> listStaff(
            String tenantCode,
            int page,
            int limit,
            String sortBy,
            String sortDir,
            List<String> role,
            String status,
            String name
    );

    List<RoleCountDTO> countStaffByRole(String tenantCode, String status, String name);

    TenantStaffResponseDTO updateStaffRole(Long id, UpdateStaffRoleRequestDTO request, Authentication caller);

    /**
     * Deactivates a staff user: revokes the Keycloak account, sets DB status to INACTIVE,
     * and publishes an analytics event. Only a STATE_ADMIN for the user's own tenant may
     * deactivate; a SUPER_USER may deactivate across tenants.
     *
     * @param id         the DB id of the staff user to deactivate
     * @param tenantCode the tenant state code (e.g. "MP")
     * @param caller     the authenticated caller
     */
    void deactivateStaff(Long id, String tenantCode, Authentication caller);

    /**
     * Activates a previously deactivated staff user by setting DB status to ACTIVE and
     * publishing an analytics event. The Keycloak account is intentionally NOT
     * re-provisioned here — {@code deactivateStaff} deletes the Keycloak user and resets
     * the managed-password placeholder, so the user's next OTP login transparently
     * re-provisions a fresh Keycloak account via {@code StaffKeycloakService.ensureKeycloakAccount}.
     *
     * <p>Authorization mirrors {@link #deactivateStaff}: only a STATE_ADMIN within the
     * user's own tenant or a SUPER_USER may activate.
     *
     * @param id         the DB id of the staff user to activate
     * @param tenantCode the tenant state code (e.g. "MP")
     * @param caller     the authenticated caller
     */
    void activateStaff(Long id, String tenantCode, Authentication caller);
}
