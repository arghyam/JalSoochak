package org.arghyam.jalsoochak.message.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class InviteEmailEvent {

    @ToString.Include
    private String eventType;
    private String to;
    private String name;
    @ToString.Include
    private String role;
    private String inviteLink;
    @ToString.Include
    private int expiryHours;

    /** State name — populated only when role is STATE_ADMIN. Null-safe in routing logic. */
    private String stateName;

    /**
     * State code of the tenant the invitee belongs to (e.g. {@code "MP"}), set by user-service.
     * Null for super-user invites and for events published before the field existed; such events
     * are served by the system default provider.
     */
    private String tenantCode;
}
