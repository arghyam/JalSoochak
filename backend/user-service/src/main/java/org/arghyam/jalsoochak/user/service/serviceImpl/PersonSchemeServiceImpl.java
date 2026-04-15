package org.arghyam.jalsoochak.user.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PersonSchemeDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingDetailDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryWithMetricsDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;
import org.arghyam.jalsoochak.user.repository.PersonSchemeRepository;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.arghyam.jalsoochak.user.util.TenantSchemaResolver;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PersonSchemeServiceImpl implements PersonSchemeService {

    private final PersonSchemeRepository personSchemeRepository;

    @Override
    public long countSchemesByPerson(String tenantCode, long personId, String schemeName) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        return personSchemeRepository.countSchemesByPerson(schemaName, personId, schemeName);
    }

    @Override
    public PageResponseDTO<PersonSchemeDetailsDTO> listSchemesByPerson(
            String tenantCode,
            long personId,
            String schemeName,
            String sortBy,
            String sortDir,
            int page,
            int size
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        int offset = page * size;
        List<PersonSchemeDetailsDTO> rows = personSchemeRepository.listSchemesByPerson(
                schemaName,
                personId,
                schemeName,
                sortBy,
                sortDir,
                offset,
                size
        );
        long total = personSchemeRepository.countSchemesByPerson(schemaName, personId, schemeName);
        return PageResponseDTO.of(rows, total, page, size);
    }

    @Override
    public SchemeDetailsWithReportingDTO getSchemeDetails(String tenantCode, long schemeId) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        return personSchemeRepository.getSchemeDetails(schemaName, schemeId);
    }

    @Override
    public PageResponseDTO<SchemeReadingSubmissionDTO> listSchemeReadings(
            String tenantCode,
            long schemeId,
            int page,
            int size
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        int offset = page * size;
        List<SchemeReadingSubmissionDTO> rows = personSchemeRepository.listSchemeReadings(
                schemaName,
                schemeId,
                offset,
                size
        );
        long total = personSchemeRepository.countSchemeReadings(schemaName, schemeId);
        return PageResponseDTO.of(rows, total, page, size);
    }

    @Override
    public PageResponseDTO<PumpOperatorSummaryWithMetricsDTO> listPumpOperatorsByPerson(
            String tenantCode,
            long personId,
            String name,
            String status,
            Integer durationDays,
            LocalDate startDate,
            LocalDate endDate,
            String sortBy,
            String sortDir,
            int page,
            int size
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        Integer statusCode = personSchemeRepository.parseStatus(status);
        int offset = page * size;
        List<PumpOperatorSummaryWithMetricsDTO> rows = personSchemeRepository.listPumpOperatorsByPerson(
                schemaName,
                personId,
                name,
                statusCode,
                durationDays,
                startDate,
                endDate,
                sortBy,
                sortDir,
                offset,
                size
        );
        long total = personSchemeRepository.countPumpOperatorsByPerson(
                schemaName,
                personId,
                name,
                statusCode,
                durationDays,
                startDate,
                endDate
        );
        return PageResponseDTO.of(rows, total, page, size);
    }

    @Override
    public PageResponseDTO<PumpOperatorReadingDetailDTO> listPumpOperatorReadings(
            String tenantCode,
            long pumpOperatorId,
            String schemeName,
            String sortBy,
            String sortDir,
            int page,
            int size
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        int offset = page * size;
        List<PumpOperatorReadingDetailDTO> rows = personSchemeRepository.listPumpOperatorReadings(
                schemaName,
                pumpOperatorId,
                schemeName,
                sortBy,
                sortDir,
                offset,
                size
        );
        long total = personSchemeRepository.countPumpOperatorReadings(schemaName, pumpOperatorId, schemeName);
        return PageResponseDTO.of(rows, total, page, size);
    }
}
