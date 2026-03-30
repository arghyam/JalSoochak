package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.dto.request.SetSystemConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.SystemConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.SystemConfigKeyEnum;

import java.util.List;
import java.util.Set;

/**
 * Service for managing system configurations.
 */
public interface SystemManagementService {

    /**
     * Get system configurations.
     *
     * @param keys Optional filter for configuration keys.
     * @return System configurations response.
     */
    SystemConfigResponseDTO getSystemConfigs(Set<SystemConfigKeyEnum> keys);

    /**
     * Update system configurations.
     *
     * @param request Update request.
     * @return Updated system configurations.
     */
    SystemConfigResponseDTO setSystemConfigs(SetSystemConfigRequestDTO request);

    /**
     * Get the list of system-supported channel codes.
     * Accessible to STATE_ADMIN and SUPER_USER for populating channel selection UI.
     *
     * @return List of channel codes configured at system level.
     */
    List<String> getSystemSupportedChannels();
}
