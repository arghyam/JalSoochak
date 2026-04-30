package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusUpdateRequestDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusesResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.common.PageResponseDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface SchemeService {

    PageResponseDTO<SchemeDTO> listSchemes(
            String tenantCode,
            int page,
            int limit,
            String sortBy,
            String sortDir,
            String stateSchemeId,
            String schemeName,
            String name,
            String workStatus,
            String operatingStatus,
            String status
    );

    PageResponseDTO<SchemeMappingDTO> listSchemeMappings(
            String tenantCode,
            int page,
            int limit,
            String sortBy,
            String sortDir,
            String name,
            String workStatus,
            String operatingStatus,
            String status,
            String villageLgdCode,
            String subDivisionName
    );

    SchemeCountsDTO getSchemeCounts(String tenantCode);

    SchemeStatusCountsDTO getSchemeStatusCounts(String tenantCode);

    SchemeStatusesResponseDTO getSchemeStatuses(Integer tenantId, int schemeId);

    void updateSchemeStatuses(String tenantCode, int schemeId, SchemeStatusUpdateRequestDTO request);

    SchemeUploadResponseDTO uploadSchemes(MultipartFile file);

    SchemeUploadResponseDTO uploadSchemeMappings(MultipartFile file);
}
