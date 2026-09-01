package org.arghyam.jalsoochak.scheme.service;

import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusUpdateRequestDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusesResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeYesterdayFinalReadingDTO;
import org.arghyam.jalsoochak.scheme.dto.ReportLinkResponseDTO;
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
            List<String> workStatus,
            List<String> operatingStatus
    );

    PageResponseDTO<SchemeMappingDTO> listSchemeMappings(
            String tenantCode,
            int page,
            int limit,
            String sortBy,
            String sortDir,
            String name,
            List<String> workStatus,
            List<String> operatingStatus,
            String villageLgdCode,
            String subDivisionName
    );

    PageResponseDTO<SchemeYesterdayFinalReadingDTO> listSchemesWithYesterdayFinalReading(String tenantCode, int page, int limit, String schemeName);

    SchemeStatusCountsDTO getSchemeStatusCounts(String tenantCode);

    SchemeStatusesResponseDTO getSchemeStatuses(Integer tenantId, int schemeId);

    void updateSchemeStatuses(String tenantCode, int schemeId, SchemeStatusUpdateRequestDTO request);

    SchemeUploadResponseDTO uploadSchemes(MultipartFile file);

    SchemeUploadResponseDTO uploadSchemeMappings(MultipartFile file);

    ReportLinkResponseDTO downloadSchemesReport();

    ReportLinkResponseDTO downloadSchemeMappingsReport();
}
