package org.arghyam.jalsoochak.tenant.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * MESSAGING-PROVIDER-SETTINGS: deployment-level switches for the per-tenant provider settings
 * checks. Bound from {@code messaging.provider.*}.
 */
@ConfigurationProperties(prefix = "messaging.provider")
@Getter
@Setter
public class MessagingProviderProperties {

    /**
     * Whether an SMTP host may resolve to a loopback, private or link-local address. For local
     * development against a container-hosted mail catcher only; it must stay {@code false} in every
     * deployed environment, where it is the check that stops a tenant setting from pointing
     * message-service at an internal service.
     */
    private boolean allowInternalHosts = false;
}
