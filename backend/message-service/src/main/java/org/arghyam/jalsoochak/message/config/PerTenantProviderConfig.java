package org.arghyam.jalsoochak.message.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * PER-TENANT-PROVIDERS: binds the three property namespaces the feature reads.
 *
 * <p>Kept separate from {@code SystemDefaultProviders}, which binds the platform's own accounts:
 * these three namespaces configure the feature itself — whether it is on, what it may connect to,
 * and the key its secrets are encrypted under — and none of them belongs to a provider.
 */
@Configuration
@EnableConfigurationProperties({
        PerTenantProviderProperties.class,
        MessagingProviderProperties.class,
        MessagingSecretProperties.class})
public class PerTenantProviderConfig {
}
