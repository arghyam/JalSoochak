package org.arghyam.jalsoochak.user.config;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses to start the service when Single Tenant Mode is switched on for a database that already
 * holds more than one ACTIVE tenant.
 *
 * <p>{@code SINGLE_TENANT_MODE} is purely declarative — nothing else reconciles it against the
 * tenant table. The guard in {@code TenantManagementServiceImpl.createTenant} only protects the
 * forward direction (it refuses to onboard a second tenant while the flag is on); it cannot stop
 * a deployment that already has several tenants from being started with the flag set.
 *
 * <p>This check matters most in <strong>this</strong> service. user-service owns login entirely
 * ({@code AuthController} /login, /refresh, /staff/otp/verify), and {@link JwtAuthConverter}
 * expands {@code SUPER_STATE_ADMIN} into {@code SUPER_USER} + {@code STATE_ADMIN} whenever Single
 * Tenant Mode is on. With several tenants present that expansion is a cross-tenant data-isolation
 * breach, and tenant-service failing to boot would neither stop a single login nor revoke the
 * privilege. So user-service must refuse to start too.
 *
 * <p>"More than one ACTIVE tenant" means {@code status = ACTIVE} only. Note that DEGRADED tenants
 * are also loginable ({@link org.arghyam.jalsoochak.user.util.TenantAccessValidator#isAccessibleToStaff}
 * permits ACTIVE and DEGRADED), so they are reported at WARN but deliberately do not fail startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SingleTenantModeStartupValidator {

    /** Maximum number of state codes named in a log or failure message. */
    private static final int MAX_LISTED_STATE_CODES = 10;

    /**
     * PostgreSQL SQLStates that mean the tenant table itself is absent: {@code 42P01}
     * (undefined_table) and {@code 3F000} (invalid_schema_name). Only these two justify starting
     * with the invariant unverified — no table means no tenants.
     *
     * <p>Every other SQLState leaves the table possibly full of ACTIVE tenants that simply were not
     * read, so it must fail startup. That includes {@code 42703} (undefined_column) and
     * {@code 42501} (insufficient_privilege), which are in the same SQLState class 42 and therefore
     * also arrive as {@code BadSqlGrammarException}: catching that exception type alone would let a
     * renamed column or a revoked SELECT grant boot the deployment unchecked.
     */
    private static final Set<String> MISSING_TENANT_TABLE_SQL_STATES = Set.of("42P01", "3F000");

    private final AppProperties appProperties;
    private final UserCommonRepository userCommonRepository;

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
            List<String> active = userCommonRepository.findActiveTenantStateCodes();
            if (active.size() > 1) {
                throw new IllegalStateException(buildFailureMessage(active));
            }

            List<String> degraded = userCommonRepository.findDegradedTenantStateCodes();
            if (active.size() + degraded.size() > 1) {
                log.warn("Single Tenant Mode is on and {} tenant(s) are ACTIVE, but {} DEGRADED"
                        + " tenant(s) are also loginable ({}). Staff of those tenants can still"
                        + " sign in, and the SUPER_STATE_ADMIN expansion reaches their data. This"
                        + " check enforces the ACTIVE count only, so startup continued.",
                        active.size(), degraded.size(), formatStateCodes(degraded));
            }

            log.info("Single Tenant Mode verified: {} ACTIVE tenant(s) in common_schema.", active.size());
        } catch (DataAccessException e) {
            if (!isMissingTenantTable(e)) {
                // The database is reachable but the query failed (bad column, missing privileges,
                // connectivity). Single Tenant Mode must not come up with the invariant unverified.
                throw new IllegalStateException(
                        "app.single-tenant-mode is true (SINGLE_TENANT_MODE=true) but the ACTIVE tenant"
                        + " count could not be read from common_schema.tenant_master_table, so the"
                        + " single-tenant invariant cannot be verified. Fix database connectivity or"
                        + " set SINGLE_TENANT_MODE=false to run in Multi Tenant Mode.", e);
            }
            // common_schema.tenant_master_table is not there at all. Flyway is disabled in every
            // service (spring.flyway.enabled=false), so a fresh environment can legitimately boot
            // before the migrations in backend/database/ have been applied. With no table there are
            // no tenants, so the invariant cannot be violated.
            log.warn("Single Tenant Mode could not be verified: common_schema.tenant_master_table"
                    + " is not present. Apply the migrations in backend/database/ and restart.", e);
        }
    }

    /**
     * Whether {@code e} reports that {@code common_schema.tenant_master_table} does not exist, as
     * opposed to any other failure to read it. Anything without a recognised SQLState — including a
     * {@code null} one — counts as "not missing" so that the unverified case fails startup.
     */
    private static boolean isMissingTenantTable(DataAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                return sqlState != null && MISSING_TENANT_TABLE_SQL_STATES.contains(sqlState);
            }
        }
        return false;
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
