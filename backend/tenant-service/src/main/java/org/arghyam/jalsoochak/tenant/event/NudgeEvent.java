package org.arghyam.jalsoochak.tenant.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One nudge for one pump operator for one day, however many of their schemes are still pending.
 * The chatbot flow asks which scheme when the operator has more than one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NudgeEvent {
    private String eventType;
    private String recipientPhone;
    private String operatorName;
    private Integer tenantId;
    private Integer languageId;
    private Long userId;
    private Long whatsappConnectionId;
    private String tenantSchema;
    /** The IST calendar day being nudged for, ISO-8601 ({@code yyyy-MM-dd}). */
    private String nudgeDate;
    /** How many of the operator's schemes had nothing recorded when the nudge was built (logging only). */
    private Integer pendingSchemeCount;
}
