package org.arghyam.jalsoochak.message.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.arghyam.jalsoochak.message.dto.TenantProviderSecretRow;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.dto.TenantSecretKeyRow;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.arghyam.jalsoochak.message.exception.SecretCryptoException;
import org.arghyam.jalsoochak.message.repository.TenantProviderSecretRepository;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PER-TENANT-PROVIDERS: turns a tenant's stored ciphertext into the credentials its provider needs
 * (S-3).
 *
 * <p>The one thing that makes this safe is what it does <em>not</em> take: a caller names a tenant,
 * a channel and the names its provider requires, and each row's location is derived from those
 * (S-1), against a closed set of names per channel. There is no
 * parameter that can point at another tenant's row, and no reference stored in the settings that
 * could (O2-8) — which is the difference between this and the {@code env:} convention the OCR
 * resolver uses, where the value being resolved is named by whoever wrote the settings.
 *
 * <p>Decryption is envelope-shaped: the row names its key version, that version's wrapped data key
 * is unwrapped under the master key it names, and the secret is decrypted under the data key with
 * an AAD built from the row's own coordinates. A ciphertext that has been moved between tenants,
 * channels, names or versions therefore fails authentication rather than decrypting to the wrong
 * tenant's credential (S-7). The unwrapped data key is zeroised in a {@code finally} block.
 *
 * <p>Nothing here logs a value, and no exception message carries one (S-4). A failure names the
 * location: tenant, channel, secret name, key version.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantSecretResolver {

    private final TenantProviderSecretRepository secretRepository;
    private final SecretCryptoService cryptoService;

    /**
     * Resolves every secret a provider requires, in one pass.
     *
     * <p>All-or-nothing: if any required name is missing the result is empty, because a provider
     * built with half its credentials fails at the provider instead of falling back here, and O2-9
     * puts that failure on the wrong side of the line — a missing credential is a configuration
     * problem, which must be answered with the system default.
     *
     * @return the resolved credentials, or empty when the tenant is unknown, no name is required,
     *         or at least one required name is not stored
     * @throws IllegalArgumentException if a required name is outside the channel's closed set
     * @throws SecretCryptoException if a stored value exists but cannot be decrypted — a wrong or
     *                               absent master key, a tampered row, or a key version whose row
     *                               is missing. Never a fallback: a credential that does not
     *                               authenticate is not returned in any form.
     */
    public Optional<TenantSecrets> resolveAll(TenantRef tenant, MessagingChannel channel,
            Set<String> requiredNames) {
        if (tenant == null || tenant.id() == null || requiredNames.isEmpty()) {
            return Optional.empty();
        }
        for (String name : requiredNames) {
            if (!channel.supportsSecret(name)) {
                // Not a caller's typo to be forgiven: the channel's secret names are a closed set,
                // and a name outside it is a coding error that would otherwise read as "credential
                // missing" and send every one of this tenant's messages through the system default
                // forever. Checked before the read, so a bad name costs no query.
                throw new IllegalArgumentException("Channel " + channel + " has no secret named '"
                        + name + "'. Supported: " + channel.getSecretNames());
            }
        }
        Map<String, TenantProviderSecretRow> stored = new LinkedHashMap<>();
        for (TenantProviderSecretRow row : secretRepository.findByTenantAndChannel(tenant.id(), channel)) {
            stored.put(row.secretName(), row);
        }
        // Presence first, decryption second. Interleaving them would decrypt a credential and then
        // throw it away on discovering the next one is absent, and it would make the number of
        // decryptions depend on the iteration order of the caller's set.
        for (String name : requiredNames) {
            if (!stored.containsKey(name)) {
                log.warn("[Providers] {} is missing secret '{}' for channel {}; the system default will"
                        + " be used", tenant, name, channel);
                return Optional.empty();
            }
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String name : requiredNames) {
            resolved.put(name, decrypt(tenant.id(), stored.get(name)));
        }
        return Optional.of(TenantSecrets.of(channel, resolved));
    }

    private String decrypt(Integer tenantId, TenantProviderSecretRow row) {
        TenantSecretKeyRow key = secretRepository.findKey(tenantId, row.keyVersion())
                .orElseThrow(() -> new SecretCryptoException(
                        "No data key for " + row + ": the secret names a key version that is not stored"));
        byte[] dataKey = cryptoService.unwrapDataKey(
                key.wrappedKey(), tenantId, key.keyVersion(), key.masterKeyId());
        try {
            return cryptoService.decryptSecret(row.ciphertext(), dataKey, tenantId,
                    row.channel(), row.secretName(), row.keyVersion());
        } finally {
            // The data key unlocks every secret this tenant has, so it does not outlive the one
            // value it was fetched for.
            SecretCryptoService.zeroise(dataKey);
        }
    }
}
