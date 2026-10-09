package org.arghyam.jalsoochak.message.event;

import lombok.Data;
import lombok.ToString;

@Data
@ToString(exclude = {"to", "resetLink"})
public class ResetPasswordEmailEvent {

    private String eventType;
    private String to;
    private String resetLink;
    private int expiryMinutes;

    /**
     * Tenant the recipient belongs to, set by user-service. Null for super users and for events
     * published before the field existed; such events are served by the system default provider.
     */
    private Integer tenantId;

    /** State code of {@link #tenantId} (e.g. {@code "MP"}). Null under the same conditions. */
    private String tenantCode;

    /**
     * The recipient's {@code common_schema.tenant_admin_user_master_table} id, for the delivery ledger.
     * Null for events published before the field existed.
     */
    private Long adminUserId;
}
