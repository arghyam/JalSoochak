package org.arghyam.jalsoochak.tenant.config;

import java.util.List;

import org.arghyam.jalsoochak.tenant.config.properties.AppProperties;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses to start the service when Single Tenant Mode is switched on for a database that already
 * holds more than one ACTIVE tenant.
 *
 * <p>{@code SINGLE_TENANT_MODE} is purely declarative — nothing else reconciles it against the
 * tenant table. {@code TenantManagementServiceImpl.createTenant} only protects the forward
 * direction (it refuses to onboard a second tenant while the flag is on); it cannot stop a
 * deployment that already has several tenants from being started with the flag set. In that state
 * the {@code SUPER_STATE_ADMIN} expansion in {@code JwtAuthConverter} hands its holders
 * {@code SUPER_USER} + {@code STATE_ADMIN} across every tenant, which is a cross-tenant
 * data-isolation breach rather than a mere misconfiguration. So the deployment must not boot.
 *
 * <p>The same check exists in user-service and scheme-service. It has to: login is owned entirely
 * by user-service, and both it and scheme-service perform the role expansion, so tenant-service
 * refusing to boot on its own would stop no logins and revoke no privileges.
 *
 * <p>"More than one ACTIVE tenant" means {@code status = ACTIVE} only. Note that DEGRADED tenants
 * are also loginable ({@code TenantAccessValidator.isAccessibleToStaff} permits ACTIVE and
 * DEGRADED), so they are reported at WARN but deliberately do not fail startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SingleTenantModeStartupValidator {

    /** Maximum number of state codes named in a log or failure message. */
    private static final int MAX_LISTED_STATE_CODES = 10;

    private final AppProperties appProperties;
    private final TenantCommonRepository tenantCommonRepository;

    /**
     * Validates the Single Tenant Mode invariant during context startup.
     *
     * <p>Package-private so unit tests can invoke it directly without standing up a Spring
     * context, following the {@code GlificWhatsAppService.validateTemplates()} precedent.
     *
     * @throws IllegalStateException if more than one tenant is ACTIVE while Single Tenant Mode is
     *                               on, or if the tenant table could not be read at all
     */
    @PostConstruct
    void validateSingleTenantMode() {
        if (!appProperties.isSingleTenantMode()) {
            return; // Multi Tenant Mode: any number of tenants is legitimate.
        }

        try {
            List<String> active = tenantCommonRepository.findActiveTenantStateCodes();
            if (active.size() > 1) {
                throw new IllegalStateException(buildFailureMessage(active));
            }

            List<String> degraded = tenantCommonRepository.findDegradedTenantStateCodes();
            if (active.size() + degraded.size() > 1) {
                log.warn("Single Tenant Mode is on and {} tenant(s) are ACTIVE, but {} DEGRADED"
                        + " tenant(s) are also loginable ({}). Staff of those tenants can still"
                        + " sign in, and the SUPER_STATE_ADMIN expansion reaches their data. This"
                        + " check enforces the ACTIVE count only, so startup continued.",
                        active.size(), degraded.size(), formatStateCodes(degraded));
            }

            log.info("Single Tenant Mode verified: {} ACTIVE tenant(s) in common_schema.", active.size());
        } catch (BadSqlGrammarException e) {
            // common_schema.tenant_master_table is not there. Flyway is disabled in every service
            // (spring.flyway.enabled=false), so a fresh environment can legitimately boot before
            // the migrations in backend/database/ have been applied. With no table there are no
            // tenants, so the invariant cannot be violated.
            log.warn("Single Tenant Mode could not be verified: common_schema.tenant_master_table"
                    + " is not present. Apply the migrations in backend/database/ and restart.", e);
        } catch (DataAccessException e) {
            // The database is reachable but the query failed (permissions, connectivity). Single
            // Tenant Mode must not come up with the invariant unverified.
            throw new IllegalStateException(
                    "app.single-tenant-mode is true (SINGLE_TENANT_MODE=true) but the ACTIVE tenant"
                    + " count could not be read from common_schema.tenant_master_table, so the"
                    + " single-tenant invariant cannot be verified. Fix database connectivity or"
                    + " set SINGLE_TENANT_MODE=false to run in Multi Tenant Mode.", e);
        }
    }

    private static String buildFailureMessage(List<String> activeStateCodes) {
        return "app.single-tenant-mode is true (SINGLE_TENANT_MODE=true) but " + activeStateCodes.size()
                + " tenants are ACTIVE in common_schema.tenant_master_table: "
                + formatStateCodes(activeStateCodes)
                + ". Single Tenant Mode expands SUPER_STATE_ADMIN into SUPER_USER + STATE_ADMIN,"
                + " which would grant those roles access across all of them. Either set"
                + " SINGLE_TENANT_MODE=false to run this deployment in Multi Tenant Mode, or leave"
                + " exactly one tenant ACTIVE.";
    }

    private static String formatStateCodes(List<String> stateCodes) {
        if (stateCodes.size() <= MAX_LISTED_STATE_CODES) {
            return String.join(", ", stateCodes);
        }
        return String.join(", ", stateCodes.subList(0, MAX_LISTED_STATE_CODES))
                + " and " + (stateCodes.size() - MAX_LISTED_STATE_CODES) + " more";
    }
}
