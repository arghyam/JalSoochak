package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PersonSchemeDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingDetailDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryWithMetricsDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;

public interface PersonSchemeService {

    long countSchemesByPerson(String tenantCode, long personId, String schemeName);

    PageResponseDTO<PersonSchemeDetailsDTO> listSchemesByPerson(
            String tenantCode,
            long personId,
            String schemeName,
            String sortBy,
            String sortDir,
            int page,
            int size
    );

    SchemeDetailsWithReportingDTO getSchemeDetails(String tenantCode, long schemeId);

    PageResponseDTO<SchemeReadingSubmissionDTO> listSchemeReadings(
            String tenantCode,
            long schemeId,
            int page,
            int size
    );

    PageResponseDTO<PumpOperatorSummaryWithMetricsDTO> listPumpOperatorsByPerson(
            String tenantCode,
            long personId,
            String name,
            String status,
            Integer durationDays,
            String sortBy,
            String sortDir,
            int page,
            int size
    );

    PageResponseDTO<PumpOperatorReadingDetailDTO> listPumpOperatorReadings(
            String tenantCode,
            long pumpOperatorId,
            String schemeName,
            String sortBy,
            String sortDir,
            int page,
            int size
    );
}
