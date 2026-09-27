package org.arghyam.jalsoochak.telemetry.service.water;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;

/**
 * Builds a real {@link SupplyPlausibilityGuard} for tests of the services that depend on one.
 *
 * <p>A real guard rather than a mock, so a test that turns the check on exercises the actual
 * gathering, parsing and policy rather than a stub of them. The registry is null throughout — the
 * counters are null-guarded exactly so tests need not stand up a {@code MeterRegistry}.
 */
public final class SupplyPlausibilityFixtures {

    /** The shipped defaults: AUDIT, 150 L/person/day, 5 persons per household. */
    public static final String DEFAULT_LIMIT = "150";
    public static final String DEFAULT_MEMBERS = "5";

    private SupplyPlausibilityFixtures() {
    }

    public static SupplyPlausibilityProperties properties(SupplyPlausibilityProperties.Mode mode) {
        return properties(mode, DEFAULT_LIMIT, DEFAULT_MEMBERS);
    }

    public static SupplyPlausibilityProperties properties(SupplyPlausibilityProperties.Mode mode,
                                                          String limitPerPersonLitres,
                                                          String defaultMembersPerHousehold) {
        SupplyPlausibilityProperties properties = new SupplyPlausibilityProperties();
        properties.setMode(mode.name());
        properties.setLimitPerPersonLitres(limitPerPersonLitres);
        properties.setDefaultMembersPerHousehold(defaultMembersPerHousehold);
        properties.init();
        return properties;
    }

    public static SupplyPlausibilityGuard guard(SupplyPlausibilityProperties.Mode mode,
                                                TelemetryTenantRepository telemetryTenantRepository,
                                                TenantConfigRepository tenantConfigRepository) {
        return guard(properties(mode), telemetryTenantRepository, tenantConfigRepository);
    }

    public static SupplyPlausibilityGuard guard(SupplyPlausibilityProperties properties,
                                                TelemetryTenantRepository telemetryTenantRepository,
                                                TenantConfigRepository tenantConfigRepository) {
        return new SupplyPlausibilityGuard(
                telemetryTenantRepository, tenantConfigRepository, new ObjectMapper(), properties, null);
    }
}
