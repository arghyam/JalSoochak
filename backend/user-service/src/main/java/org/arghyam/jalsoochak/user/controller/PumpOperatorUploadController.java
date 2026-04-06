package org.arghyam.jalsoochak.user.controller;

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

@RestController
@RequestMapping("/api/v1/state-admin/pump-operators")
@RequiredArgsConstructor
@Slf4j
public class PumpOperatorUploadController {

    private final PumpOperatorUploadService pumpOperatorUploadService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseDTO<PumpOperatorUploadResponseDTO>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader
    ) {
        try {
            PumpOperatorUploadResponseDTO res = pumpOperatorUploadService.uploadPumpOperatorMappings(file, authorizationHeader);
            return ResponseEntity.ok(ApiResponseDTO.of(200, "Upload processed", res));
        } catch (Exception e) {
            log.error("Logout failed for session: {}", e.getMessage());
            throw e;
        }
    }
}
