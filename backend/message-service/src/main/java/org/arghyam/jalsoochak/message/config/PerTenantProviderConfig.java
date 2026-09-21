package org.arghyam.jalsoochak.message.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * PER-TENANT-PROVIDERS: binds the three property namespaces the feature reads.
 *
 * <p>Follows {@code MailConfig}'s shape rather than putting
 * {@code @EnableConfigurationProperties} on the application class, so the feature's configuration
 * stays findable from one place.
 */
@Configuration
@EnableConfigurationProperties({
        PerTenantProviderProperties.class,
        MessagingProviderProperties.class,
        MessagingSecretProperties.class})
public class PerTenantProviderConfig {
}
