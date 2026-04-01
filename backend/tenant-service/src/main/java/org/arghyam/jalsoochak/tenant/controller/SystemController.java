package org.arghyam.jalsoochak.tenant.controller;

import java.util.List;
import java.util.Set;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetSystemConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.SystemConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.SystemConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.service.SystemManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "System Configuration", description = "Endpoints for managing global system settings")
@CommonApiResponses
public class SystemController {

    private final SystemManagementService systemManagementService;

    @Operation(summary = "Get system configurations", description = "Retrieves global platform settings. Super User only.")
    @ApiResponse(responseCode = "200", description = "System configurations retrieved successfully")
    @PreAuthorize("hasRole('SUPER_USER')")
    @GetMapping("/config")
    public ResponseEntity<ApiResponseDTO<SystemConfigResponseDTO>> getSystemConfigs(
            @RequestParam(required = false) Set<SystemConfigKeyEnum> keys) {
        log.info("GET /api/v1/system/config [keys={}]", keys);
        SystemConfigResponseDTO response = systemManagementService.getSystemConfigs(keys);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "System configurations retrieved successfully", response));
    }

    @Operation(summary = "Get system-supported channels",
            description = "Returns the list of channel codes enabled at platform level. Used by State Admin to populate channel selection during tenant configuration.")
    @ApiResponse(responseCode = "200", description = "System supported channels retrieved successfully")
    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    @GetMapping("/channels")
    public ResponseEntity<ApiResponseDTO<List<String>>> getSystemSupportedChannels() {
        log.info("GET /api/v1/system/channels");
        List<String> channels = systemManagementService.getSystemSupportedChannels();
        return ResponseEntity.ok(ApiResponseDTO.of(200, "System supported channels retrieved successfully", channels));
    }

    @Operation(summary = "Set system configurations", description = "Updates global system settings. Super User only.")
    @ApiResponse(responseCode = "200", description = "System configurations set successfully")
    @PreAuthorize("hasRole('SUPER_USER')")
    @PutMapping("/config")
    public ResponseEntity<ApiResponseDTO<SystemConfigResponseDTO>> setSystemConfigs(
            @Valid @RequestBody SetSystemConfigRequestDTO request) {
        log.info("PUT /api/v1/system/config");
        SystemConfigResponseDTO response = systemManagementService.setSystemConfigs(request);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "System configurations set successfully", response));
    }
}
