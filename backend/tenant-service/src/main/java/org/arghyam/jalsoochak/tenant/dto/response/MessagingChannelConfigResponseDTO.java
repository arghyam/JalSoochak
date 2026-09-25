package org.arghyam.jalsoochak.tenant.dto.response;

import java.util.Map;

import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.enums.SecretStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MESSAGING-PROVIDER-SETTINGS: one channel's stored settings together with the status of the
 * credentials those settings need.
 *
 * <p>The two are reported side by side because either alone is misleading. Settings with a MISSING
 * credential do not make the tenant's provider usable — message-service falls back to the system
 * default — and a stored credential with no settings does nothing at all. {@code usable} states the
 * conclusion so a caller does not have to re-derive it from the provider's required names.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A channel's provider settings and the SET/MISSING status of its credentials")
public class MessagingChannelConfigResponseDTO {

    private MessagingChannel channel;

    /**
     * {@code EmailProviderConfigDTO} or {@code SmsProviderConfigDTO}, or null when the tenant has no
     * settings for this channel and therefore uses the system default provider.
     */
    @Schema(description = "Stored settings, or null when the tenant uses the system default provider")
    private ConfigValueDTO settings;

    @Schema(description = "Secret name to status", example = "{\"authKey\": \"SET\", \"authToken\": \"MISSING\"}")
    private Map<String, SecretStatus> secrets;

    /** The data key version the stored secrets are encrypted under, or null if none are stored. */
    private Integer keyVersion;

    /**
     * True when settings are present and every credential the chosen provider needs is SET. False
     * means message-service will use the system default for this channel.
     */
    @Schema(description = "Whether the tenant's own provider will be used for this channel")
    private boolean usable;
}
