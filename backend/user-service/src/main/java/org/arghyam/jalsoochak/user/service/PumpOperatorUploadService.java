package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.response.PumpOperatorUploadResponseDTO;
import org.springframework.web.multipart.MultipartFile;

public interface PumpOperatorUploadService {
    PumpOperatorUploadResponseDTO uploadPumpOperatorMappings(MultipartFile file, String authorizationHeader);
    PumpOperatorUploadResponseDTO uploadUserSchemeMappings(MultipartFile file, String authorizationHeader);
}
