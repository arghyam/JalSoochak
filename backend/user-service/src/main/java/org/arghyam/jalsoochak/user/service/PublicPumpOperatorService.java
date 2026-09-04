package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsWithComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSchemeComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemePumpOperatorsDTO;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;

import java.time.LocalDate;
import java.util.List;

public interface PublicPumpOperatorService {

    PumpOperatorDetailsDTO getPumpOperatorDetails(
            String tenantCode,
            long pumpOperatorId,
            Long schemeId,
            LocalDate startDate,
            LocalDate endDate
    );

    /**
     * Operator detail keyed on the random v4 {@code uuid} rather than the sequential id — the
     * lookup the anonymous public dashboard uses, so that operator records cannot be enumerated.
     */
    PumpOperatorDetailsDTO getPumpOperatorDetailsByUuid(
            String tenantCode,
            String uuid,
            Long schemeId,
            LocalDate startDate,
            LocalDate endDate
    );

    PumpOperatorReadingComplianceDTO getReadingCompliance(String tenantCode, long pumpOperatorId);

    PumpOperatorDetailsWithComplianceDTO getPumpOperatorDetailsWithCompliance(String tenantCode, long pumpOperatorId);

    PageResponseDTO<PumpOperatorReadingComplianceRowDTO> listReadingCompliance(String tenantCode, int page, int size);

    PageResponseDTO<PumpOperatorSchemeComplianceRowDTO> listPumpOperatorsBySchemeWithCompliance(
            String tenantCode,
            long schemeId,
            Long pumpOperatorId,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int size
    );

    List<SchemePumpOperatorsDTO> listPumpOperatorsByScheme(
            String tenantCode,
            List<Long> schemeIds,
            String schemeName,
            Integer page,
            Integer size
    );
}
