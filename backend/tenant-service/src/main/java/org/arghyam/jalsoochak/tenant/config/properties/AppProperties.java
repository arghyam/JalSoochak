package org.arghyam.jalsoochak.tenant.config.properties;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application-level configuration properties for deployment mode and feature flags.
 * Supports both Single Tenant Mode (STM) and Multi-Tenant Mode (MTM).
 */
@ConfigurationProperties(prefix = "app")
@Component
@Getter
@Setter
public class AppProperties {

    private static final String SINGLE_TENANT = "SINGLE_TENANT";
    private static final String MULTI_TENANT = "MULTI_TENANT";

    /**
     * Deployment mode: SINGLE_TENANT or MULTI_TENANT.
     * Defaults to MULTI_TENANT for backward compatibility.
     */
    private String deploymentMode = MULTI_TENANT;

    /**
     * Validates that deploymentMode is one of the recognized values.
     * Fails fast at startup if the configuration is invalid.
     */
    @PostConstruct
    public void validateDeploymentMode() {
        if (!SINGLE_TENANT.equalsIgnoreCase(deploymentMode) && !MULTI_TENANT.equalsIgnoreCase(deploymentMode)) {
            throw new IllegalStateException(
                    "Invalid app.deployment-mode value: '" + deploymentMode
                    + "'. Allowed values: SINGLE_TENANT, MULTI_TENANT");
        }
    }

    /**
     * Checks if the application is running in Single Tenant Mode.
     *
     * @return true if deployment mode is SINGLE_TENANT, false otherwise
     */
    public boolean isSingleTenantMode() {
        return SINGLE_TENANT.equalsIgnoreCase(deploymentMode);
    }

    /**
     * Checks if the application is running in Multi-Tenant Mode.
     *
     * @return true if deployment mode is MULTI_TENANT, false otherwise
     */
    public boolean isMultiTenantMode() {
        return !isSingleTenantMode();
    }
}
