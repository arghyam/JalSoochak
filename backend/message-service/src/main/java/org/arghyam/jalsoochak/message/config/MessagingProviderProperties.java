package org.arghyam.jalsoochak.message.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * PER-TENANT-PROVIDERS: deployment-level switches for the provider endpoint checks. Bound from
 * {@code messaging.provider.*}.
 *
 * <p>The read-side twin of tenant-service's {@code config/properties/MessagingProviderProperties},
 * bound from the same environment variable. The two must agree: a host tenant-service accepted on
 * write and this service refuses before connecting is a tenant whose mail silently falls back to
 * the system default.
 */
@ConfigurationProperties(prefix = "messaging.provider")
@Getter
@Setter
public class MessagingProviderProperties {

    /**
     * Whether a tenant's SMTP host may resolve to a loopback, private or link-local address. For
     * local development against a container-hosted mail catcher only; it must stay {@code false} in
     * every deployed environment, where it is the check that stops a tenant setting from pointing
     * this service at an internal service.
     */
    private boolean allowInternalHosts = false;
}
