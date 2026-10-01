package org.arghyam.jalsoochak.user.controller.bulk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorUploadResponseDTO;
import org.arghyam.jalsoochak.user.service.PumpOperatorUploadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A state admin's bulk uploads of pump operators and of user-scheme mappings, each from a spreadsheet.
 *
 * <p>Both routes are {@code permitAll} in {@code SecurityConfig}. The service requires a STATE_ADMIN
 * token itself, through {@code UploadAuthService}.
 */
@RestController
@RequestMapping("/api/v1/state-admin")
@RequiredArgsConstructor
@Slf4j
public class BulkUploadController {

    private final PumpOperatorUploadService pumpOperatorUploadService;

    @PostMapping(value = "/pump-operators/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseDTO<PumpOperatorUploadResponseDTO>> uploadPumpOperators(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader
    ) {
        try {
            PumpOperatorUploadResponseDTO res = pumpOperatorUploadService.uploadPumpOperatorMappings(file, authorizationHeader);
            return ResponseEntity.ok(ApiResponseDTO.of(200, "Upload processed", res));
        } catch (Exception e) {
            log.error("Pump operator upload failed: {}", e.getMessage());
            throw e;
        }
    }

    @PostMapping(value = "/user-scheme-mappings/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseDTO<PumpOperatorUploadResponseDTO>> uploadUserSchemeMappings(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader
    ) {
        try {
            PumpOperatorUploadResponseDTO res = pumpOperatorUploadService.uploadUserSchemeMappings(file, authorizationHeader);
            return ResponseEntity.ok(ApiResponseDTO.of(200, "Upload processed", res));
        } catch (Exception e) {
            log.error("User scheme mapping upload failed: {}", e.getMessage());
            throw e;
        }
    }
}
