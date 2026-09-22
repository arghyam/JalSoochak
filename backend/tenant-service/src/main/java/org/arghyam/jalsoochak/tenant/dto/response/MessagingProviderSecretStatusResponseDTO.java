package org.arghyam.jalsoochak.tenant.dto.response;

import java.util.Map;

import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.enums.SecretStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MESSAGING-PROVIDER-SECRETS: which of a channel's secrets are stored.
 *
 * <p>Every name the channel supports is listed, not just the stored ones, so a caller can
 * see what is still needed before the tenant's provider will be used. Statuses only —
 * this is the read model for a write-only store.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Stored/missing status for each secret a messaging channel supports. Never contains values.")
public class MessagingProviderSecretStatusResponseDTO {

    private Integer tenantId;

    private MessagingChannel channel;

    @Schema(description = "Secret name to status", example = "{\"authKey\": \"SET\", \"authToken\": \"MISSING\"}")
    private Map<String, SecretStatus> secrets;

    /** The key version the stored secrets are encrypted under, or {@code null} if none are. */
    @Schema(description = "Data key version the stored secrets use")
    private Integer keyVersion;
}
