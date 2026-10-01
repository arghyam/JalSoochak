package org.arghyam.jalsoochak.tenant.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MESSAGING-PROVIDER-SECRETS: outcome of a master-key rewrap.
 *
 * <p>The counts are what tells ops when the outgoing master key can be removed from the
 * environment: only once {@code rewrapped + alreadyActive == totalKeys} and
 * {@code failedTenantIds} is empty does no row still depend on it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of re-wrapping every tenant data key under the active master key")
public class MessagingSecretRewrapResponseDTO {

    private String activeMasterKeyId;

    /** Every key row in the platform, retired versions included. */
    private int totalKeys;

    private int rewrapped;

    /** Rows already wrapped under the active master key — left untouched. */
    private int alreadyActive;

    /**
     * Tenants with at least one key that could not be re-wrapped, usually because the
     * master key that wrapped it is no longer configured. Reported rather than thrown, so
     * one unreadable row cannot block the rest of the rotation.
     */
    private List<Integer> failedTenantIds;
}
