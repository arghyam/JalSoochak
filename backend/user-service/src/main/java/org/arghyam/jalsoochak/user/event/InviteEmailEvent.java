package org.arghyam.jalsoochak.user.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InviteEmailEvent {

    private String eventType;
    private String to;
    private String name;
    private String role;
    private String inviteLink;
    private int expiryHours;

    /** State name — populated only when role is STATE_ADMIN. Omitted from JSON when null. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String stateName;
}
