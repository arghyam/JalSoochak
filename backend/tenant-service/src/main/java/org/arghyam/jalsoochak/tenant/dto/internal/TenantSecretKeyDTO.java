package org.arghyam.jalsoochak.tenant.dto.internal;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MESSAGING-PROVIDER-SECRETS: a row of {@code common_schema.tenant_secret_key} — a
 * tenant's data key, still wrapped.
 *
 * <p>No {@code @Data}: the generated {@code toString()} would print {@code wrappedKey}.
 * It is ciphertext and useless without the master key, but a wrapped key in a log is
 * still half of a credential sitting in a place with different retention rules from the
 * database, so it is kept out of every rendering.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantSecretKeyDTO {

    private Integer id;
    private Integer tenantId;
    private Integer keyVersion;
    private String wrappedKey;
    private String masterKeyId;
    private String status;
    private LocalDateTime createdAt;
    private Integer createdBy;
    private LocalDateTime updatedAt;
    private Integer updatedBy;

    @Override
    public String toString() {
        return "TenantSecretKeyDTO(tenantId=" + tenantId + ", keyVersion=" + keyVersion
                + ", masterKeyId=" + masterKeyId + ", status=" + status + ")";
    }
}
