package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LogoSource;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantLogoResult;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetTenantConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.UpdateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.GenerateApiTokenResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantSummaryResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyEditConstraintsResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import java.util.List;
import java.util.Set;

/**
 * Service for managing tenants and their configurations.
 */
public interface TenantManagementService {

    /**
     * Creates a new tenant.
     * 
     * @param request Tenant creation request.
     * @return Created tenant response.
     */
    TenantResponseDTO createTenant(CreateTenantRequestDTO request);

    /**
     * Updates an existing tenant.
     * 
     * @param tenantId ID of the tenant.
     * @param request  Tenant update request.
     * @return Updated tenant response.
     */
    TenantResponseDTO updateTenant(Integer tenantId, UpdateTenantRequestDTO request);

    /**
     * Deactivates a tenant.
     * 
     * @param tenantId ID of the tenant.
     */
    void deactivateTenant(Integer tenantId);

    /**
     * Gets all tenants with optional filters.
     *
     * @param page   Page number (0-indexed).
     * @param size   Page size.
     * @param status Optional status filter; {@code null} returns all statuses.
     * @param search Optional case-insensitive partial match on tenant name; {@code null} or blank means no filter.
     * @return Page of tenants.
     */
    PageResponseDTO<TenantResponseDTO> getAllTenants(int page, int size, TenantStatusEnum status, String search);

    /**
     * Returns an aggregate status summary (total, active, inactive, archived)
     * for all non-system tenants.
     *
     * @return Tenant summary response.
     */
    TenantSummaryResponseDTO getTenantSummary();

    /**
     * Gets tenant configurations.
     * 
     * @param tenantId ID of the tenant.
     * @param keys     Optional filter for configuration keys.
     * @return Tenant configurations response.
     */
    TenantConfigResponseDTO getTenantConfigs(Integer tenantId, Set<TenantConfigKeyEnum> keys);

    /**
     * Sets tenant configurations.
     *
     * @param tenantId ID of the tenant.
     * @param request  Tenant configuration request.
     * @return Tenant configurations response.
     */
    TenantConfigResponseDTO setTenantConfigs(Integer tenantId, SetTenantConfigRequestDTO request);

    /**
     * Sets the tenant logo from either a file upload or an external URL.
     * For {@link LogoSource.FileSource}: uploads the file to internal storage and saves the object key.
     * For {@link LogoSource.UrlSource}: validates and saves the URL directly.
     * In both cases, if the previous logo was a managed object (not an external URL),
     * it is deleted from storage after a successful DB upsert.
     *
     * @param tenantId ID of the tenant.
     * @param source   Exactly one of {@link LogoSource.FileSource} or {@link LogoSource.UrlSource}.
     * @return Updated config response containing the new TENANT_LOGO value.
     */
    TenantConfigResponseDTO setTenantLogo(Integer tenantId, LogoSource source);

    /**
     * Resolves the logo for the given tenant.
     * Returns {@link TenantLogoResult.Managed} when the logo is stored in internal
     * object storage, or {@link TenantLogoResult.External} when an external URL was
     * configured via PUT /logo.
     *
     * @param tenantId ID of the tenant.
     * @return resolved logo result.
     */
    TenantLogoResult resolveTenantLogo(Integer tenantId);

    /**
     * Returns the configuration completeness status for a tenant.
     * Each known config key is listed with a CONFIGURED or PENDING status,
     * along with an aggregate summary.
     *
     * @param tenantId ID of the tenant.
     * @return Configuration status response.
     */
    TenantConfigStatusResponseDTO getTenantConfigStatus(Integer tenantId);

    /**
     * Gets the location hierarchy configuration for a tenant.
     * 
     * @param hierarchyType Type of hierarchy: LGD or DEPARTMENT
     * @return Location hierarchy configuration with levels and names.
     */
    LocationHierarchyResponseDTO getLocationHierarchy(Integer tenantId, String hierarchyType);

    /**
     * Gets child locations by parent ID.
     *
     * @param hierarchyType Type of hierarchy: LGD or DEPARTMENT
     * @param parentId      Parent location ID (null for root-level locations)
     * @return List of child location records.
     */
    List<LocationResponseDTO> getLocationChildren(Integer tenantId, String hierarchyType, Integer parentId);

    /**
     * Returns edit constraints for the given location hierarchy type.
     * Tells the caller whether structural changes (add/remove levels) are permitted,
     * based on whether seeded data exists in the master table.
     *
     * @param tenantId      ID of the tenant.
     * @param hierarchyType LGD or DEPARTMENT
     * @return Edit constraints response.
     */
    LocationHierarchyEditConstraintsResponseDTO getLocationHierarchyEditConstraints(
            Integer tenantId, String hierarchyType);

    /**
     * Updates the location hierarchy for the given type.
     * Internally decides the update strategy:
     * - If no seeded data exists: full structural update (delete + insert).
     * - If seeded data exists and only level names changed: updates names in-place.
     * - If seeded data exists and structure differs: throws LocationHierarchyStructureLockedException (409).
     *
     * @param tenantId      ID of the tenant.
     * @param hierarchyType LGD or DEPARTMENT
     * @param levels        New hierarchy level definitions.
     * @return Updated location hierarchy.
     */
    LocationHierarchyResponseDTO updateLocationHierarchy(
            Integer tenantId, String hierarchyType, List<LocationLevelConfigDTO> levels);

    /**
     * Generates a new API token for the tenant identified by {@code stateCode} (create-or-replace semantics).
     * The raw token is returned exactly once; only its SHA-256 hash is persisted.
     * Calling this again immediately invalidates the previous token.
     *
     * @param stateCode the tenant's state code, extracted from the caller's JWT.
     * @return DTO containing the raw token (shown once).
     */
    GenerateApiTokenResponseDTO generateApiToken(String stateCode);

}
