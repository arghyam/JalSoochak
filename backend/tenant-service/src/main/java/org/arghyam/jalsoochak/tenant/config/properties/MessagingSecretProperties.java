package org.arghyam.jalsoochak.tenant.config.properties;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * MESSAGING-PROVIDER-SECRETS: master-key (KEK) configuration for the tenant messaging
 * secret store. Bound from the {@code messaging.secret.*} namespace.
 *
 * <p>Several master keys may be configured at once so a rotation can read ciphertext
 * wrapped under the outgoing key while writing under the incoming one. Only
 * {@link #getActiveMasterKeyId()} is ever used to wrap; every configured id can unwrap.
 *
 * <p>Leaving the map empty is a supported state, not an error: a deployment that does
 * not use per-tenant providers needs no new environment variable, and the secret
 * endpoints answer {@code 503} instead of the service refusing to start. Key material
 * is validated and held by {@code SecretCryptoService}, never by this class — a
 * {@code @ConfigurationProperties} bean is printed by the actuator's configprops
 * endpoint and appears in binding failure messages.
 */
@ConfigurationProperties(prefix = "messaging.secret")
@Getter
@Setter
public class MessagingSecretProperties {

    /** Id of the key new wraps use, e.g. {@code v1}. Must be a key in {@link #masterKeys}. */
    private String activeMasterKeyId;

    /** Key id to base64-encoded 32-byte key. Blank values are treated as absent. */
    private Map<String, String> masterKeys = new LinkedHashMap<>();
}
