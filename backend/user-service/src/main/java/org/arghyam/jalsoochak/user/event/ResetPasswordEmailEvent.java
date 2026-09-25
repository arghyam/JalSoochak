package org.arghyam.jalsoochak.user.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResetPasswordEmailEvent {

    private String eventType;
    private String to;
    private String resetLink;
    private int expiryMinutes;

    /**
     * Tenant the recipient belongs to. Null for super users, who deliberately carry no tenant
     * and are served by the system default provider. Optional and additive — omitted from JSON
     * when null.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer tenantId;

    /** State code of {@link #tenantId} (e.g. {@code "MP"}). Optional, see {@link #tenantId}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String tenantCode;
}
