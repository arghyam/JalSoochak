package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.dto.event.DepartmentLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.LgdLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantLocationHierarchyUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserSchemeMappingsReplacedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.IncludedWorkStatusesUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.RegularityThresholdUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterNormUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterSupplyThresholdUpdatedEvent;
import org.arghyam.jalsoochak.analytics.entity.DimDepartmentLocation;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimScheme;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.entity.DimTenantWaterNorm;
import org.arghyam.jalsoochak.analytics.entity.DimTenantWorkStatusFilter;
import org.arghyam.jalsoochak.analytics.entity.DimUser;
import org.arghyam.jalsoochak.analytics.repository.DimDepartmentLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimSchemeRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantWaterNormRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantWorkStatusFilterRepository;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.arghyam.jalsoochak.analytics.service.DimensionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DimensionServiceImpl implements DimensionService {

    /** The national-default tenant: a config-only singleton, never created by TENANT_CREATED. */
    private static final Integer NATIONAL_TENANT_ID = 0;
    /** Status stamped on the synthetic national-default row (excluded from tenant enumerations). */
    private static final int NATIONAL_TENANT_STATUS = 1;

    private final DimTenantRepository dimTenantRepository;
    private final DimUserRepository dimUserRepository;
    private final DimSchemeRepository dimSchemeRepository;
    private final DimLgdLocationRepository dimLgdLocationRepository;
    private final DimDepartmentLocationRepository dimDepartmentLocationRepository;
    private final DimTenantWaterNormRepository dimTenantWaterNormRepository;
    private final DimTenantWorkStatusFilterRepository dimTenantWorkStatusFilterRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void upsertTenant(TenantEvent event) {
        DimTenant tenant = dimTenantRepository.findById(event.getTenantId())
                .orElse(DimTenant.builder()
                        .tenantId(event.getTenantId())
                        .createdAt(LocalDateTime.now())
                        .build());

        tenant.setStateCode(event.getStateCode());
        tenant.setTitle(event.getTitle());
        tenant.setCountryCode(event.getCountryCode() != null ? event.getCountryCode() : "IN");
        tenant.setStatus(event.getStatus());
        tenant.setUpdatedAt(LocalDateTime.now());

        dimTenantRepository.save(tenant);
        log.info("Upserted dim_tenant_table [id={}]", event.getTenantId());
    }

    @Override
    @Transactional
    public void upsertUser(UserEvent event) {
        if (event.getTenantId() == null || event.getTenantId() == 0) {
            log.debug("Skipping dim_user upsert for SUPER_USER [userId={}]", event.getUserId());
            return;
        }

        // Tenant-scoped key is the source of truth for user upsert.
        // Use top-by-updated ordering to avoid crashes when legacy duplicates exist.
        DimUser user = dimUserRepository.findTopByTenantIdAndUserIdOrderByUpdatedAtDescCreatedAtDesc(
                        event.getTenantId(), event.getUserId())
                .orElse(DimUser.builder()
                        .userId(event.getUserId())
                        .createdAt(LocalDateTime.now())
                        .build());

        user.setTenantId(event.getTenantId());
        user.setEmail(event.getEmail());
        user.setUserType(event.getUserType());
        user.setUuid(event.getUuid());
        if (event.getTitle() != null)
            user.setTitle(event.getTitle());
        if (event.getStatus() != null)
            user.setStatus(event.getStatus());
        user.setUpdatedAt(LocalDateTime.now());

        dimUserRepository.save(user);
        log.info("Upserted dim_user_table [uuid={}, userId={}]", event.getUuid(), event.getUserId());
    }

    @Override
    @Transactional
    public void replaceUserSchemeMappings(UserSchemeMappingsReplacedEvent event) {
        Integer userId = event.getUserId();
        Integer tenantId = event.getTenantId();
        if (userId == null || tenantId == null) {
            log.debug("Skipping dim_user_scheme_mapping replace: missing userId/tenantId");
            return;
        }

        int mappingStatus = event.getStatus() != null ? event.getStatus() : 1;
        Set<Integer> schemeIds = new LinkedHashSet<>();
        if (event.getSchemeIds() != null) {
            for (Integer schemeId : event.getSchemeIds()) {
                if (schemeId != null) {
                    schemeIds.add(schemeId);
                }
            }
        }

        jdbcTemplate.update("""
                        DELETE FROM analytics_schema.dim_user_scheme_mapping_table
                        WHERE tenant_id = ? AND user_id = ?
                        """,
                tenantId, userId);

        for (Integer schemeId : schemeIds) {
            jdbcTemplate.update("""
                            INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                                (uuid, user_id, scheme_id, status, tenant_id, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                            """,
                    UUID.randomUUID(), userId, schemeId, mappingStatus, tenantId);
        }

        log.info("Replaced dim_user_scheme_mapping_table rows [tenantId={}, userId={}, schemeCount={}]",
                tenantId, userId, schemeIds.size());
    }

    @Override
    @Transactional
    public void upsertScheme(SchemeEvent event) {
        DimScheme scheme = dimSchemeRepository.findTopByTenantIdAndSchemeIdOrderByUpdatedAtDescCreatedAtDesc(
                        event.getTenantId(), event.getSchemeId())
                .orElse(DimScheme.builder()
                        .schemeId(event.getSchemeId())
                        .createdAt(LocalDateTime.now())
                        .build());

        scheme.setTenantId(event.getTenantId());
        scheme.setSchemeName(event.getSchemeName());
        scheme.setStateSchemeId(event.getStateSchemeId());
        scheme.setCentreSchemeId(event.getCentreSchemeId());
        scheme.setLongitude(event.getLongitude());
        scheme.setLatitude(event.getLatitude());

        scheme.setParentLgdLocationId(event.getParentLgdLocationId());
        scheme.setLevel1LgdId(event.getLevel1LgdId());
        scheme.setLevel2LgdId(event.getLevel2LgdId());
        scheme.setLevel3LgdId(event.getLevel3LgdId());
        scheme.setLevel4LgdId(event.getLevel4LgdId());
        scheme.setLevel5LgdId(event.getLevel5LgdId());
        scheme.setLevel6LgdId(event.getLevel6LgdId());

        scheme.setParentDepartmentLocationId(event.getParentDepartmentLocationId());
        scheme.setLevel1DeptId(event.getLevel1DeptId());
        scheme.setLevel2DeptId(event.getLevel2DeptId());
        scheme.setLevel3DeptId(event.getLevel3DeptId());
        scheme.setLevel4DeptId(event.getLevel4DeptId());
        scheme.setLevel5DeptId(event.getLevel5DeptId());
        scheme.setLevel6DeptId(event.getLevel6DeptId());

        scheme.setOperatingStatus(event.getStatus());
        scheme.setWorkStatus(event.getWorkStatus());
        scheme.setUpdatedAt(LocalDateTime.now());

        dimSchemeRepository.save(scheme);
        log.info("Upserted dim_scheme_table [id={}]", event.getSchemeId());
    }

    @Override
    @Transactional
    public void upsertLgdLocation(LgdLocationEvent event) {
        DimLgdLocation loc = dimLgdLocationRepository.findById(event.getLgdId())
                .orElse(DimLgdLocation.builder()
                        .lgdId(event.getLgdId())
                        .createdAt(LocalDateTime.now())
                        .build());

        loc.setTenantId(event.getTenantId());
        loc.setLgdCode(event.getLgdCode());
        loc.setLgdCName(event.getLgdCName());
        loc.setTitle(event.getTitle());
        loc.setLgdLevel(event.getLgdLevel());
        loc.setLevel1LgdId(event.getLevel1LgdId());
        loc.setLevel2LgdId(event.getLevel2LgdId());
        loc.setLevel3LgdId(event.getLevel3LgdId());
        loc.setLevel4LgdId(event.getLevel4LgdId());
        loc.setLevel5LgdId(event.getLevel5LgdId());
        loc.setLevel6LgdId(event.getLevel6LgdId());
        loc.setGeom(parseGeoJson(event.getGeom()));
        loc.setUpdatedAt(LocalDateTime.now());

        dimLgdLocationRepository.save(loc);
        log.info("Upserted dim_lgd_location_table [id={}]", event.getLgdId());
    }

    @Override
    @Transactional
    public void upsertDepartmentLocation(DepartmentLocationEvent event) {
        DimDepartmentLocation dept = dimDepartmentLocationRepository.findById(event.getDepartmentId())
                .orElse(DimDepartmentLocation.builder()
                        .departmentId(event.getDepartmentId())
                        .createdAt(LocalDateTime.now())
                        .build());

        dept.setTenantId(event.getTenantId());
        dept.setDepartmentCName(event.getDepartmentCName());
        dept.setTitle(event.getTitle());
        dept.setDepartmentLevel(event.getDepartmentLevel());
        dept.setLevel1DeptId(event.getLevel1DeptId());
        dept.setLevel2DeptId(event.getLevel2DeptId());
        dept.setLevel3DeptId(event.getLevel3DeptId());
        dept.setLevel4DeptId(event.getLevel4DeptId());
        dept.setLevel5DeptId(event.getLevel5DeptId());
        dept.setLevel6DeptId(event.getLevel6DeptId());
        dept.setUpdatedAt(LocalDateTime.now());

        dimDepartmentLocationRepository.save(dept);
        log.info("Upserted dim_department_location_table [id={}]", event.getDepartmentId());
    }

    @Override
    @Transactional
    public void updateWaterNorm(WaterNormUpdatedEvent event) {
        DimTenant tenant = dimTenantRepository.findById(event.getTenantId())
                .orElseThrow(() -> new IllegalStateException(
                        "No dim_tenant_table row for tenantId=" + event.getTenantId()));
        Integer newLpcd = event.getWaterNorm();

        // 1) Keep dim_tenant_table as the "current" convenience copy.
        tenant.setRequiredLpcd(newLpcd);
        tenant.setUpdatedAt(LocalDateTime.now());
        dimTenantRepository.save(tenant);

        // 2) Maintain the SCD-2 history so historical aggregates stay reproducible.
        applyWaterNormChange(event.getTenantId(), tenant, newLpcd, null, null);

        log.info("Updated water norm required_lpcd={} [tenantId={}]",
                newLpcd, event.getTenantId());
    }

    /**
     * Record a water-norm change in the SCD-2 history: when any norm field actually
     * changes, close the open row and open a new one (half-open intervals). Any
     * {@code null} override is carried forward from the current open row (or the
     * tenant copy when no open row exists yet), so both LPCD and supply-threshold
     * changes are tracked. The close is flushed before the insert so the
     * "one open row per tenant" partial unique index never sees two open rows.
     */
    private void applyWaterNormChange(Integer tenantId, DimTenant tenant,
                                      Integer newLpcd, Integer newOverPct, Integer newUnderPct) {
        LocalDate today = LocalDate.now();
        DimTenantWaterNorm open =
                dimTenantWaterNormRepository.findByTenantIdAndEffectiveToIsNull(tenantId).orElse(null);

        Integer lpcd = newLpcd != null ? newLpcd
                : (open != null ? open.getRequiredLpcd() : tenant.getRequiredLpcd());
        Integer persons = open != null ? open.getPersonCountPerHousehold() : tenant.getPersonCountPerHousehold();
        Integer overPct = newOverPct != null ? newOverPct
                : (open != null ? open.getOverSupplyRangePercentage() : tenant.getOverSupplyRangePercentage());
        Integer underPct = newUnderPct != null ? newUnderPct
                : (open != null ? open.getUnderSupplyRangePercentage() : tenant.getUnderSupplyRangePercentage());

        if (open != null) {
            if (Objects.equals(open.getRequiredLpcd(), lpcd)
                    && Objects.equals(open.getPersonCountPerHousehold(), persons)
                    && Objects.equals(open.getOverSupplyRangePercentage(), overPct)
                    && Objects.equals(open.getUnderSupplyRangePercentage(), underPct)) {
                return; // no real change — keep the timeline stable
            }
            open.setEffectiveTo(today);
            dimTenantWaterNormRepository.saveAndFlush(open);
        }

        dimTenantWaterNormRepository.save(DimTenantWaterNorm.builder()
                .tenantId(tenantId)
                .effectiveFrom(today)
                .effectiveTo(null)
                .requiredLpcd(lpcd)
                .personCountPerHousehold(persons)
                .overSupplyRangePercentage(overPct)
                .underSupplyRangePercentage(underPct)
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Override
    @Transactional
    public void updateIncludedWorkStatuses(IncludedWorkStatusesUpdatedEvent event) {
        DimTenant tenant = findOrMaterialiseConfigTenant(event.getTenantId(), event.getStateCode());
        tenant.setIncludedWorkStatuses(event.getWorkStatuses());
        tenant.setUpdatedAt(LocalDateTime.now());
        dimTenantRepository.save(tenant);

        // Maintain the SCD-2 filter history (mirrors applyWaterNormChange) so aggregation
        // rebuilds keep using the filter that was in force for each historical period.
        applyWorkStatusFilterChange(event.getTenantId(), event.getWorkStatuses());

        log.info("Updated dim_tenant_table.included_work_statuses={} [tenantId={}]",
                event.getWorkStatuses(), event.getTenantId());
    }

    /**
     * Record a work-status filter change in the SCD-2 history: when the set actually
     * changes, close the open row (half-open interval) and open a new one effective
     * today. Applies to both the per-tenant tier ({@code tenantId > 0}) and the
     * national tier ({@code tenantId == 0}). The close is flushed before the insert
     * so the "one open row per tenant" partial unique index never sees two open rows.
     */
    private void applyWorkStatusFilterChange(Integer tenantId, List<Integer> newStatuses) {
        LocalDate today = LocalDate.now();
        DimTenantWorkStatusFilter open =
                dimTenantWorkStatusFilterRepository.findByTenantIdAndEffectiveToIsNull(tenantId).orElse(null);

        if (open != null) {
            if (Objects.equals(normalized(open.getIncludedWorkStatuses()), normalized(newStatuses))) {
                return; // no real change — keep the timeline stable
            }
            open.setEffectiveTo(today);
            dimTenantWorkStatusFilterRepository.saveAndFlush(open);
        }

        dimTenantWorkStatusFilterRepository.save(DimTenantWorkStatusFilter.builder()
                .tenantId(tenantId)
                .effectiveFrom(today)
                .effectiveTo(null)
                .includedWorkStatuses(newStatuses)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** Order-insensitive comparison basis for the filter set (null and empty are equivalent). */
    private static List<Integer> normalized(List<Integer> statuses) {
        if (statuses == null) {
            return List.of();
        }
        return statuses.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    @Override
    @Transactional
    public void updateRegularityThreshold(RegularityThresholdUpdatedEvent event) {
        DimTenant tenant = findOrMaterialiseConfigTenant(event.getTenantId(), event.getStateCode());
        tenant.setRegularityThresholdPercent(event.getThresholdPercent());
        tenant.setUpdatedAt(LocalDateTime.now());
        dimTenantRepository.save(tenant);
        log.info("Updated dim_tenant_table.regularity_threshold_percent={} [tenantId={}]",
                event.getThresholdPercent(), event.getTenantId());
    }

    /**
     * Resolves the {@code dim_tenant_table} row a config event writes to.
     *
     * <p>The national default (tenantId=0) is a config-only singleton: no TENANT_CREATED event ever
     * creates it, so it must be materialised on first write instead of throwing. Real tenants still
     * require an existing row (TENANT_CREATED precedes any config change on the same ordered topic).</p>
     */
    private DimTenant findOrMaterialiseConfigTenant(Integer tenantId, String stateCode) {
        return dimTenantRepository.findById(tenantId)
                .orElseGet(() -> {
                    if (!NATIONAL_TENANT_ID.equals(tenantId)) {
                        throw new IllegalStateException("No dim_tenant_table row for tenantId=" + tenantId);
                    }
                    return DimTenant.builder()
                            .tenantId(tenantId)
                            .stateCode(stateCode)
                            .title(stateCode)
                            .status(NATIONAL_TENANT_STATUS)
                            .createdAt(LocalDateTime.now())
                            .build();
                });
    }

    @Override
    @Transactional
    public void updateLocationHierarchyNames(TenantLocationHierarchyUpdatedEvent event) {
        if (event.getLevels() == null || event.getLevels().isEmpty()) {
            log.info("No levels to update [tenantId={}, hierarchyType={}]",
                    event.getTenantId(), event.getHierarchyType());
            return;
        }
        boolean isLgd = "LGD".equalsIgnoreCase(event.getHierarchyType());
        for (TenantLocationHierarchyUpdatedEvent.LevelEntry entry : event.getLevels()) {
            if (isLgd) {
                List<DimLgdLocation> locs = dimLgdLocationRepository
                        .findByTenantIdAndLgdLevel(event.getTenantId(), entry.getLevel());
                locs.forEach(l -> {
                    l.setLgdCName(entry.getName());
                    l.setUpdatedAt(LocalDateTime.now());
                });
                dimLgdLocationRepository.saveAll(locs);
            } else {
                List<DimDepartmentLocation> locs = dimDepartmentLocationRepository
                        .findByTenantIdAndDepartmentLevel(event.getTenantId(), entry.getLevel());
                locs.forEach(l -> {
                    l.setDepartmentCName(entry.getName());
                    l.setUpdatedAt(LocalDateTime.now());
                });
                dimDepartmentLocationRepository.saveAll(locs);
            }
        }
        log.info("Updated location hierarchy names [tenantId={}, hierarchyType={}]",
                event.getTenantId(), event.getHierarchyType());
    }

    @Override
    @Transactional
    public void updateWaterSupplyThreshold(WaterSupplyThresholdUpdatedEvent event) {
        DimTenant tenant = dimTenantRepository.findById(event.getTenantId())
                .orElseThrow(() -> new IllegalStateException(
                        "No dim_tenant_table row for tenantId=" + event.getTenantId()));
        tenant.setUnderSupplyRangePercentage(event.getUnderSupplyThresholdPercent());
        tenant.setOverSupplyRangePercentage(event.getOverSupplyThresholdPercent());
        tenant.setUpdatedAt(LocalDateTime.now());
        dimTenantRepository.save(tenant);

        // Track threshold changes in the SCD-2 history too (they feed the efficient-range calc).
        applyWaterNormChange(event.getTenantId(), tenant, null,
                event.getOverSupplyThresholdPercent(), event.getUnderSupplyThresholdPercent());

        log.info("Updated dim_tenant_table supply thresholds [tenantId={}, under={}, over={}]",
                event.getTenantId(), event.getUnderSupplyThresholdPercent(), event.getOverSupplyThresholdPercent());
    }

    private Geometry parseGeoJson(String geoJson) {
        if (geoJson == null || geoJson.isBlank())
            return null;
        try {
            GeoJsonReader reader = new GeoJsonReader();
            return reader.read(geoJson);
        } catch (Exception e) {
            log.warn("Could not parse GeoJSON: {}", e.getMessage());
            return null;
        }
    }
}
