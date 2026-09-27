package org.arghyam.jalsoochak.message.dto;

import org.arghyam.jalsoochak.message.enums.MessagingChannel;

/**
 * PER-TENANT-PROVIDERS: a row of {@code common_schema.tenant_provider_secret} — one encrypted
 * credential, still encrypted.
 *
 * <p>{@code keyVersion} is carried because it is part of the AAD, so decryption needs the exact
 * version the row was written under, not whichever version is active now (S-7).
 *
 * <p>{@code ciphertext} is excluded from {@link #toString()}. It is not a plaintext leak, but a
 * ciphertext in a log is one half of an offline attack that only needs the other half, and there is
 * no reason for it to be there.
 */
public record TenantProviderSecretRow(
        int tenantId,
        MessagingChannel channel,
        String secretName,
        String ciphertext,
        int keyVersion) {

    /** Location only — never the ciphertext. */
    @Override
    public String toString() {
        return "TenantProviderSecretRow[tenantId=" + tenantId + ", channel=" + channel
                + ", secretName=" + secretName + ", keyVersion=" + keyVersion + "]";
    }
}
