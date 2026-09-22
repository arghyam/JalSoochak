package org.arghyam.jalsoochak.message.config;

import org.arghyam.jalsoochak.message.service.SecretCryptoService;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
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
 * <p>It runs in {@link PostConstruct}, the same point {@code SingleTenantModeStartupValidator}
 * checks its invariant at, because the check has to complete before any message is consumed.
 * {@code KafkaListenerEndpointRegistry} is a {@code SmartLifecycle} started inside
 * {@code finishRefresh()} and {@code ApplicationReadyEvent} is published after that, so a check
 * deferred to the event would let {@code NotificationEventRouter} drain {@code common-topic} —
 * every message in that window falling back to the platform's account — before it ever ran.
 * Spring surfaces the {@link IllegalStateException} below as the cause of a
 * {@code BeanCreationException}, so the message an operator needs is still the one they see.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PerTenantProviderStartupValidator {

    private final PerTenantProviderProperties properties;
    private final SecretCryptoService cryptoService;

    @PostConstruct
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
