package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.request.StaffReportRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.ReportResponseDTO;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.springframework.security.core.Authentication;

public interface StaffReportService {

    /**
     * Generates (or returns cached) a staff export for the calling tenant.
     *
     * <p>Cache key is {@code (report_type='TENANT_STAFF', format, params_hash, data_version)}
     * where {@code params_hash = sha256(report_type | format | normalized-filters-json)}.
     * A hit reuses the stored MinIO object and just produces a fresh presigned URL.
     */
    ReportResponseDTO generate(String tenantCode, ReportFormat format,
                               StaffReportRequestDTO filters, Authentication caller);
}
