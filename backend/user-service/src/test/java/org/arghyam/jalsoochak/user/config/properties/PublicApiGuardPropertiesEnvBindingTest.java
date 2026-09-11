package org.arghyam.jalsoochak.user.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployment charts tune the guard through environment variables, and the property prefix
 * carries a dash ({@code public-api}). Relaxed binding recognises only two spellings for it:
 * dashes as underscores ({@code PUBLIC_API_GUARD_CLIENT_IP_HEADER}) or dashes dropped
 * ({@code PUBLICAPI_GUARD_CLIENTIPHEADER}). A mixed spelling binds nothing and fails silently —
 * the guard keeps keying on the proxy's socket address, so every anonymous visitor behind the
 * ingress shares one budget and the village dashboard is blocked for all of them at once.
 */
class PublicApiGuardPropertiesEnvBindingTest {

    @Test
    void bindsTheVariableNamesTheChartsUse() {
        PublicApiGuardProperties properties = bind(Map.of(
                "PUBLIC_API_GUARD_CLIENT_IP_HEADER", "X-Forwarded-For",
                "PUBLIC_API_GUARD_BLOCKING", "false",
                "PUBLIC_API_GUARD_WARN_DISTINCT_ENTITIES", "5"));

        assertThat(properties.getClientIpHeader()).isEqualTo("X-Forwarded-For");
        assertThat(properties.isBlocking()).isFalse();
        assertThat(properties.getWarnDistinctEntities()).isEqualTo(5);
    }

    @Test
    void aMixedSpellingBindsNothing() {
        PublicApiGuardProperties properties = bind(Map.of(
                "PUBLIC_API_GUARD_CLIENTIPHEADER", "X-Forwarded-For"));

        assertThat(properties.getClientIpHeader()).isEmpty();
    }

    private static PublicApiGuardProperties bind(Map<String, Object> environment) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("public-api.guard", PublicApiGuardProperties.class)
                .orElseGet(PublicApiGuardProperties::new);
    }
}
