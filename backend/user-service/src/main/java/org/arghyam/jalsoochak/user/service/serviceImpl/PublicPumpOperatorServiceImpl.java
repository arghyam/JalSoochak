package org.arghyam.jalsoochak.user.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsWithComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSchemeComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemePumpOperatorsDTO;
import org.arghyam.jalsoochak.user.repository.PublicPumpOperatorRepository;
import org.arghyam.jalsoochak.user.service.PublicPumpOperatorService;
import org.arghyam.jalsoochak.user.util.TenantSchemaResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PublicPumpOperatorServiceImpl implements PublicPumpOperatorService {

    private final PublicPumpOperatorRepository publicPumpOperatorRepository;

    @Override
    public PumpOperatorDetailsDTO getPumpOperatorDetails(
            String tenantCode,
            long pumpOperatorId,
            Long schemeId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        PumpOperatorDetailsDTO dto = publicPumpOperatorRepository.findPumpOperatorById(
                schemaName,
                pumpOperatorId,
                schemeId,
                startDate,
                endDate
        );
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pump operator not found");
        }
        return dto;
    }

    @Override
    public PumpOperatorReadingComplianceDTO getReadingCompliance(String tenantCode, long pumpOperatorId) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        PumpOperatorReadingComplianceDTO dto = publicPumpOperatorRepository.getReadingCompliance(schemaName, pumpOperatorId);
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pump operator not found");
        }
        return dto;
    }

    @Override
    public PumpOperatorDetailsWithComplianceDTO getPumpOperatorDetailsWithCompliance(String tenantCode, long pumpOperatorId) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        PumpOperatorDetailsDTO details = publicPumpOperatorRepository.findPumpOperatorById(schemaName, pumpOperatorId);
        if (details == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pump operator not found");
        }
        PumpOperatorReadingComplianceDTO compliance = getReadingCompliance(tenantCode, pumpOperatorId);
        return PumpOperatorDetailsWithComplianceDTO.builder()
                .details(details)
                .readingCompliance(compliance)
                .build();
    }

    @Override
    public PageResponseDTO<PumpOperatorReadingComplianceRowDTO> listReadingCompliance(String tenantCode, int page, int size) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        int p = Math.max(0, page);
        int effectiveSize = clampLimit(size);
        int offset = p * effectiveSize;
        List<PumpOperatorReadingComplianceRowDTO> rows = publicPumpOperatorRepository.listReadingCompliance(schemaName, offset, effectiveSize);
        long total = publicPumpOperatorRepository.countReadingCompliance(schemaName);
        return PageResponseDTO.of(rows, total, p, effectiveSize);
    }

    @Override
    public PageResponseDTO<PumpOperatorSchemeComplianceRowDTO> listPumpOperatorsBySchemeWithCompliance(
            String tenantCode,
            long schemeId,
            Long pumpOperatorId,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int size
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        int p = Math.max(0, page);
        int effectiveSize = clampLimit(size);
        int offset = p * effectiveSize;
        List<PumpOperatorSchemeComplianceRowDTO> rows = publicPumpOperatorRepository.listPumpOperatorsBySchemeWithCompliance(
                schemaName,
                schemeId,
                pumpOperatorId,
                startDate,
                endDate,
                offset,
                effectiveSize
        );
        long total = publicPumpOperatorRepository.countPumpOperatorsBySchemeWithCompliance(
                schemaName,
                schemeId,
                pumpOperatorId,
                startDate,
                endDate
        );
        return PageResponseDTO.of(rows, total, p, effectiveSize);
    }

    @Override
    public List<SchemePumpOperatorsDTO> listPumpOperatorsByScheme(
            String tenantCode,
            List<Long> schemeIds,
            String schemeName,
            Integer page,
            Integer size
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        return publicPumpOperatorRepository.listPumpOperatorsByScheme(schemaName, schemeIds, schemeName, page, size);
    }

    private int clampLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, 100);
    }
}
