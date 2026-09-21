package org.arghyam.jalsoochak.message.config;

import org.arghyam.jalsoochak.message.service.SecretCryptoService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PER-TENANT-PROVIDERS: refuses to start when the feature is on but the deployment cannot decrypt
 * anything (§4.4).
 *
 * <p>Without the master key, every tenant that has configured its own provider silently falls back
 * to the system default: mail still goes out, from the platform's account, with an ERROR per
 * message and nothing that looks like a failure. That is the worst outcome available — a state
 * believes it is sending from its own domain and is not — so the deployment is stopped instead.
 *
 * <p>The check is conditional on the flag, which is the point: a deployment that does not use
 * per-tenant providers needs no new environment variable, so turning the feature on is the only
 * thing that makes {@code MESSAGING_SECRET_MASTER_KEY_V<n>} mandatory.
 *
 * <p>It runs on {@link ApplicationReadyEvent} rather than in a constructor so that a misconfigured
 * environment produces one clear message after the context has built, next to the other startup
 * checks in this service, rather than a bean creation failure buried in a cascade.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PerTenantProviderStartupValidator {

    private final PerTenantProviderProperties properties;
    private final SecretCryptoService cryptoService;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!properties.isEnabled()) {
            log.info("[Providers] notification.per-tenant-providers.enabled=false: every send uses the"
                    + " system default provider. No messaging master key is required.");
            return;
        }
        if (!cryptoService.isConfigured()) {
            throw new IllegalStateException(
                    "notification.per-tenant-providers.enabled=true but no messaging.secret.master-keys"
                            + " entry is configured. Without the master key this service cannot decrypt any"
                            + " tenant's provider credentials, so every configured tenant would silently"
                            + " fall back to the system default. Set MESSAGING_SECRET_ACTIVE_MASTER_KEY_ID"
                            + " and MESSAGING_SECRET_MASTER_KEY_V<n> to the same values tenant-service uses,"
                            + " or set NOTIFICATION_PER_TENANT_PROVIDERS_ENABLED=false.");
        }
        log.info("[Providers] Per-tenant providers are enabled and the messaging secret store is"
                + " readable [activeMasterKeyId={}, readableMasterKeyIds={}]",
                cryptoService.getActiveMasterKeyId(), cryptoService.getReadableMasterKeyIds());
    }
}
