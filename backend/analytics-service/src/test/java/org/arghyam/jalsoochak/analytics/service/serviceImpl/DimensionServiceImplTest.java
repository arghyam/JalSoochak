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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DimensionServiceImplTest {

    @Mock
    private DimTenantRepository dimTenantRepository;
    @Mock
    private DimUserRepository dimUserRepository;
    @Mock
    private DimSchemeRepository dimSchemeRepository;
    @Mock
    private DimLgdLocationRepository dimLgdLocationRepository;
    @Mock
    private DimDepartmentLocationRepository dimDepartmentLocationRepository;

    @InjectMocks
    private DimensionServiceImpl service;

    @Test
    void upsertTenant_setsDefaultsAndSaves() {
        TenantEvent event = new TenantEvent();
        event.setTenantId(1);
        event.setStateCode("mp");
        event.setTitle("Madhya Pradesh");
        event.setCountryCode(null);
        event.setStatus(1);
        when(dimTenantRepository.findById(1)).thenReturn(Optional.empty());

        service.upsertTenant(event);

        ArgumentCaptor<DimTenant> captor = ArgumentCaptor.forClass(DimTenant.class);
        verify(dimTenantRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getCountryCode()).isEqualTo("IN");
        assertThat(captor.getValue().getTenantId()).isEqualTo(1);
    }

    @Test
    void upsertUser_updatesExistingAndSaves() {
        UserEvent event = new UserEvent();
        event.setUserId(11);
        event.setTenantId(1);
        event.setEmail("user@test.local");
        event.setUserType(2);
        event.setUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        event.setTitle("First Last");
        event.setStatus(1);
        when(dimUserRepository.findByUuid(event.getUuid())).thenReturn(Optional.of(DimUser.builder().userId(11).build()));

        service.upsertUser(event);

        ArgumentCaptor<DimUser> captor = ArgumentCaptor.forClass(DimUser.class);
        verify(dimUserRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("user@test.local");
        assertThat(captor.getValue().getTenantId()).isEqualTo(1);
        assertThat(captor.getValue().getUuid()).isEqualTo(event.getUuid());
        assertThat(captor.getValue().getTitle()).isEqualTo("First Last");
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
    }

    @Test
    void upsertUser_superUser_tenantIdZero_skipsUpsert() {
        UserEvent event = new UserEvent();
        event.setUserId(1);
        event.setTenantId(0);
        event.setEmail("super@system.local");

        service.upsertUser(event);

        verify(dimUserRepository, never()).findById(any());
        verify(dimUserRepository, never()).save(any());
    }

    @Test
    void upsertUser_nullTenantId_skipsUpsert() {
        UserEvent event = new UserEvent();
        event.setUserId(1);
        event.setTenantId(null);
        event.setEmail("super@system.local");

        service.upsertUser(event);

        verify(dimUserRepository, never()).findById(any());
        verify(dimUserRepository, never()).save(any());
    }

    @Test
    void upsertUser_nullTitle_doesNotOverwriteExistingTitle() {
        UserEvent event = new UserEvent();
        event.setUserId(11);
        event.setTenantId(1);
        event.setEmail("user@test.local");
        event.setUserType(2);
        event.setUuid(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        event.setTitle(null);
        event.setStatus(0);

        DimUser existing = DimUser.builder().userId(11).title("Preserved Title").build();
        when(dimUserRepository.findByUuid(event.getUuid())).thenReturn(Optional.of(existing));

        service.upsertUser(event);

        ArgumentCaptor<DimUser> captor = ArgumentCaptor.forClass(DimUser.class);
        verify(dimUserRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Preserved Title");
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
    }

    @Test
    void upsertScheme_mapsHierarchyAndSaves() {
        SchemeEvent event = new SchemeEvent();
        event.setSchemeId(1001);
        event.setTenantId(1);
        event.setSchemeName("Scheme-A");
        event.setStateSchemeId(10);
        event.setCentreSchemeId(20);
        event.setParentLgdLocationId(100);
        event.setLevel1LgdId(100);
        event.setLevel2LgdId(101);
        event.setParentDepartmentLocationId(200);
        event.setLevel1DeptId(200);
        event.setLevel2DeptId(201);
        event.setStatus(1);
        when(dimSchemeRepository.findById(1001)).thenReturn(Optional.empty());

        service.upsertScheme(event);

        ArgumentCaptor<DimScheme> captor = ArgumentCaptor.forClass(DimScheme.class);
        verify(dimSchemeRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getSchemeId()).isEqualTo(1001);
        assertThat(captor.getValue().getLevel2LgdId()).isEqualTo(101);
        assertThat(captor.getValue().getLevel2DeptId()).isEqualTo(201);
    }

    @Test
    void upsertLgdLocation_whenInvalidGeoJson_setsGeomNullAndSaves() {
        LgdLocationEvent event = new LgdLocationEvent();
        event.setLgdId(101);
        event.setTenantId(1);
        event.setLgdCode("L101");
        event.setLgdCName("Child A");
        event.setTitle("Child A");
        event.setLgdLevel(2);
        event.setLevel1LgdId(100);
        event.setLevel2LgdId(101);
        event.setGeom("{invalid}");
        when(dimLgdLocationRepository.findById(101)).thenReturn(Optional.empty());

        service.upsertLgdLocation(event);

        ArgumentCaptor<DimLgdLocation> captor = ArgumentCaptor.forClass(DimLgdLocation.class);
        verify(dimLgdLocationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getGeom()).isNull();
        assertThat(captor.getValue().getTitle()).isEqualTo("Child A");
    }

    @Test
    void upsertDepartmentLocation_mapsFieldsAndSaves() {
        DepartmentLocationEvent event = new DepartmentLocationEvent();
        event.setDepartmentId(201);
        event.setTenantId(1);
        event.setDepartmentCName("Dept A");
        event.setTitle("Department A");
        event.setDepartmentLevel(2);
        event.setLevel1DeptId(200);
        event.setLevel2DeptId(201);
        when(dimDepartmentLocationRepository.findById(201)).thenReturn(Optional.empty());

        service.upsertDepartmentLocation(event);

        ArgumentCaptor<DimDepartmentLocation> captor = ArgumentCaptor.forClass(DimDepartmentLocation.class);
        verify(dimDepartmentLocationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getDepartmentLevel()).isEqualTo(2);
        assertThat(captor.getValue().getLevel2DeptId()).isEqualTo(201);
    }

    @Test
    void updateWaterNorm_setsRequiredLpcdAndSaves() {
        WaterNormUpdatedEvent event = new WaterNormUpdatedEvent("WATER_NORM_UPDATED", 1, "MP", 70);
        DimTenant existing = DimTenant.builder().tenantId(1).stateCode("MP").title("MP").status(1).build();
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(existing));

        service.updateWaterNorm(event);

        ArgumentCaptor<DimTenant> captor = ArgumentCaptor.forClass(DimTenant.class);
        verify(dimTenantRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getRequiredLpcd()).isEqualTo(70);
    }

    @Test
    void updateWaterNorm_tenantNotFound_throwsIllegalStateException() {
        WaterNormUpdatedEvent event = new WaterNormUpdatedEvent("WATER_NORM_UPDATED", 99, "XX", 55);
        when(dimTenantRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateWaterNorm(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99");
    }

    @Test
    void updateLocationHierarchyNames_lgd_updatesLgdCNameAndSavesAll() {
        TenantLocationHierarchyUpdatedEvent.LevelEntry entry =
                new TenantLocationHierarchyUpdatedEvent.LevelEntry(2, "District");
        TenantLocationHierarchyUpdatedEvent event = new TenantLocationHierarchyUpdatedEvent(
                "TENANT_LOCATION_HIERARCHY_UPDATED", 1, "MP", "LGD", List.of(entry));

        DimLgdLocation loc = DimLgdLocation.builder().lgdId(101).tenantId(1).lgdLevel(2)
                .lgdCName("OldName").build();
        when(dimLgdLocationRepository.findByTenantIdAndLgdLevel(1, 2)).thenReturn(List.of(loc));

        service.updateLocationHierarchyNames(event);

        verify(dimLgdLocationRepository, times(1)).saveAll(List.of(loc));
        assertThat(loc.getLgdCName()).isEqualTo("District");
    }

    @Test
    void updateWaterSupplyThreshold_setsThresholdFieldsAndSaves() {
        WaterSupplyThresholdUpdatedEvent event =
                new WaterSupplyThresholdUpdatedEvent("WATER_SUPPLY_THRESHOLD_UPDATED", 1, "MP", 20, 30);
        DimTenant existing = DimTenant.builder().tenantId(1).stateCode("MP").title("MP").status(1).build();
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(existing));

        service.updateWaterSupplyThreshold(event);

        ArgumentCaptor<DimTenant> captor = ArgumentCaptor.forClass(DimTenant.class);
        verify(dimTenantRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUnderSupplyRangePercentage()).isEqualTo(20);
        assertThat(captor.getValue().getOverSupplyRangePercentage()).isEqualTo(30);
    }

    @Test
    void updateWaterSupplyThreshold_tenantNotFound_throwsIllegalStateException() {
        WaterSupplyThresholdUpdatedEvent event =
                new WaterSupplyThresholdUpdatedEvent("WATER_SUPPLY_THRESHOLD_UPDATED", 99, "XX", 20, 30);
        when(dimTenantRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateWaterSupplyThreshold(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99");
    }

    @Test
    void updateLocationHierarchyNames_department_updatesDepartmentCNameAndSavesAll() {
        TenantLocationHierarchyUpdatedEvent.LevelEntry entry =
                new TenantLocationHierarchyUpdatedEvent.LevelEntry(1, "Zone");
        TenantLocationHierarchyUpdatedEvent event = new TenantLocationHierarchyUpdatedEvent(
                "TENANT_LOCATION_HIERARCHY_UPDATED", 1, "MP", "DEPARTMENT", List.of(entry));

        DimDepartmentLocation dept = DimDepartmentLocation.builder().departmentId(201).tenantId(1)
                .departmentLevel(1).departmentCName("OldZone").build();
        when(dimDepartmentLocationRepository.findByTenantIdAndDepartmentLevel(1, 1)).thenReturn(List.of(dept));

        service.updateLocationHierarchyNames(event);

        verify(dimDepartmentLocationRepository, times(1)).saveAll(List.of(dept));
        assertThat(dept.getDepartmentCName()).isEqualTo("Zone");
    }
}
