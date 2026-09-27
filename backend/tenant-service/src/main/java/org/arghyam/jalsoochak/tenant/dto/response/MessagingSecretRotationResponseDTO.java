package org.arghyam.jalsoochak.tenant.dto.response;

import java.util.List;

import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MESSAGING-PROVIDER-SECRETS: outcome of rotating one tenant's data key.
 *
 * <p>Unlike a master-key rewrap, this re-encrypts the tenant's secret ciphertexts, so the
 * old key version is of no further use and is retired.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of issuing a tenant a new data key and re-encrypting its secrets under it")
public class MessagingSecretRotationResponseDTO {

    private Integer tenantId;

    /** The version that was retired. */
    private Integer previousKeyVersion;

    private Integer newKeyVersion;

    private int secretsReEncrypted;

    /** Channels that had at least one secret moved to the new key version. */
    private List<MessagingChannel> channels;
}
