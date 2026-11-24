package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tenant-wide report settings. Each absent field means message-service's env value (URL, phone) or
 * the built-in format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportSettingsDTO(String dashboardUrl, String supportPhone, DateFormatsDTO dateFormats,
                                NumberFormatDTO numberFormat) {
}
