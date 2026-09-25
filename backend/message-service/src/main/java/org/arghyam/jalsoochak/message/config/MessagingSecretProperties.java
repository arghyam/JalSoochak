package org.arghyam.jalsoochak.message.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * PER-TENANT-PROVIDERS: master-key (KEK) configuration for the tenant messaging secret store.
 * Bound from the {@code messaging.secret.*} namespace.
 *
 * <p>The read-side twin of tenant-service's {@code config/properties/MessagingSecretProperties},
 * and it must be configured with <em>the same</em> keys: tenant-service wraps each tenant's data
 * key under one of these, and this service is the only other holder that can unwrap it (§4.1).
 * Staging and production deliberately use different keys, so a dump restored across environments
 * fails to decrypt instead of quietly using the wrong credentials (S-6).
 *
 * <p>Several master keys may be configured at once so a rotation can read ciphertext wrapped under
 * the outgoing key while tenant-service writes under the incoming one. This service never wraps,
 * but it still needs {@code active-master-key-id} set, because {@code SecretCryptoService} treats
 * "no active id" as "the store is switched off".
 *
 * <p>Leaving the map empty is a supported state: a deployment with
 * {@code notification.per-tenant-providers.enabled=false} needs no new environment variable. With
 * the flag on it is not — {@code PerTenantProviderStartupValidator} refuses to start, because the
 * alternative is every configured tenant silently falling back to the system default. Key material
 * is validated and held by {@code SecretCryptoService}, never by this class: a
 * {@code @ConfigurationProperties} bean is printed by the actuator's configprops endpoint and
 * appears in binding failure messages.
 */
@ConfigurationProperties(prefix = "messaging.secret")
@Getter
@Setter
public class MessagingSecretProperties {

    /** Id of the key wraps are read under, e.g. {@code v1}. Must be a key in {@link #masterKeys}. */
    private String activeMasterKeyId;

    /** Key id to base64-encoded 32-byte key. Blank values are treated as absent. */
    private Map<String, String> masterKeys = new LinkedHashMap<>();
}
