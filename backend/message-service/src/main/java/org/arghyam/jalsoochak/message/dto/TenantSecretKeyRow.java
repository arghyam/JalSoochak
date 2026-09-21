package org.arghyam.jalsoochak.message.dto;

/**
 * PER-TENANT-PROVIDERS: a row of {@code common_schema.tenant_secret_key} — one tenant's data key,
 * wrapped under the master key named by {@code masterKeyId}.
 *
 * <p>{@code wrappedKey} is ciphertext, never usable material: unwrapping it needs a master key that
 * exists only in the deployment environment (§4.1). It is still key-shaped, so it is deliberately
 * kept out of {@link #toString()}, which reaches logs through collection printing and debuggers.
 */
public record TenantSecretKeyRow(
        int tenantId,
        int keyVersion,
        String wrappedKey,
        String masterKeyId,
        String status) {

    /** Location and rotation state only — never the wrapped key. */
    @Override
    public String toString() {
        return "TenantSecretKeyRow[tenantId=" + tenantId + ", keyVersion=" + keyVersion
                + ", masterKeyId=" + masterKeyId + ", status=" + status + "]";
    }
}
