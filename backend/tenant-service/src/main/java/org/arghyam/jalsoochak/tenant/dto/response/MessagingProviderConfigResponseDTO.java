package org.arghyam.jalsoochak.tenant.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MESSAGING-PROVIDER-SETTINGS: a tenant's messaging provider configuration, both channels.
 *
 * <p>Settings and secret status, never a secret value — there is no response shape anywhere in this
 * feature that carries one back out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A tenant's email and SMS provider configuration. Never contains credentials.")
public class MessagingProviderConfigResponseDTO {

    private Integer tenantId;

    private MessagingChannelConfigResponseDTO email;

    private MessagingChannelConfigResponseDTO sms;
}
