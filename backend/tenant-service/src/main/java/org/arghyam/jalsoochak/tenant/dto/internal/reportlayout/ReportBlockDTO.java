package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import java.util.Map;

import org.arghyam.jalsoochak.tenant.enums.reportlayout.ReportRole;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One report's block. The roles listed are exactly the roles that receive the report, in document
 * order; an empty map means nobody does.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportBlockDTO(Map<ReportRole, RoleLayoutDTO> roles) {
    public ReportBlockDTO {
        roles = Copies.map(roles);
    }
}
