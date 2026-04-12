package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.dto.event.DepartmentLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.LgdLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantLocationHierarchyUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterNormUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterSupplyThresholdUpdatedEvent;
import org.arghyam.jalsoochak.analytics.entity.DimDepartmentLocation;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimScheme;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.entity.DimUser;
import org.arghyam.jalsoochak.analytics.repository.DimDepartmentLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimSchemeRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.arghyam.jalsoochak.analytics.service.DimensionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DimensionServiceImpl implements DimensionService {

    private final DimTenantRepository dimTenantRepository;
    private final DimUserRepository dimUserRepository;
    private final DimSchemeRepository dimSchemeRepository;
    private final DimLgdLocationRepository dimLgdLocationRepository;
    private final DimDepartmentLocationRepository dimDepartmentLocationRepository;

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

        scheme.setStatus(event.getStatus());
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
        tenant.setRequiredLpcd(event.getWaterNorm());
        tenant.setUpdatedAt(LocalDateTime.now());
        dimTenantRepository.save(tenant);
        log.info("Updated dim_tenant_table.required_lpcd={} [tenantId={}]",
                event.getWaterNorm(), event.getTenantId());
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
