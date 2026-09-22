package org.arghyam.jalsoochak.tenant.dto.internal;

import java.time.LocalDateTime;

import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MESSAGING-PROVIDER-SECRETS: a row of {@code common_schema.tenant_provider_secret} —
 * one encrypted credential.
 *
 * <p>No {@code @Data}, for the same reason as {@link TenantSecretKeyDTO}: {@code toString()}
 * renders the row's identity and never {@code ciphertext}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantProviderSecretDTO {

    private Integer id;
    private String uuid;
    private Integer tenantId;
    private MessagingChannel channel;
    private String secretName;
    private String ciphertext;
    private Integer keyVersion;
    private LocalDateTime createdAt;
    private Integer createdBy;
    private LocalDateTime updatedAt;
    private Integer updatedBy;

    @Override
    public String toString() {
        return "TenantProviderSecretDTO(tenantId=" + tenantId + ", channel=" + channel
                + ", secretName=" + secretName + ", keyVersion=" + keyVersion + ")";
    }
}
