package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.dto.event.DepartmentLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.IncludedWorkStatusesUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserSchemeMappingsReplacedEvent;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    private DimTenantWaterNormRepository dimTenantWaterNormRepository;
    @Mock
    private DimTenantWorkStatusFilterRepository dimTenantWorkStatusFilterRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

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
        when(dimUserRepository.findTopByTenantIdAndUserIdOrderByUpdatedAtDescCreatedAtDesc(
                event.getTenantId(), event.getUserId()))
                .thenReturn(Optional.of(DimUser.builder().userId(11).build()));

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
        when(dimUserRepository.findTopByTenantIdAndUserIdOrderByUpdatedAtDescCreatedAtDesc(
                event.getTenantId(), event.getUserId()))
                .thenReturn(Optional.of(existing));

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
        when(dimSchemeRepository.findTopByTenantIdAndSchemeIdOrderByUpdatedAtDescCreatedAtDesc(1, 1001))
                .thenReturn(Optional.empty());

        service.upsertScheme(event);

        ArgumentCaptor<DimScheme> captor = ArgumentCaptor.forClass(DimScheme.class);
        verify(dimSchemeRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getSchemeId()).isEqualTo(1001);
        assertThat(captor.getValue().getLevel2LgdId()).isEqualTo(101);
        assertThat(captor.getValue().getLevel2DeptId()).isEqualTo(201);
    }

    @Test
    void upsertScheme_persistsWorkStatus() {
        SchemeEvent event = new SchemeEvent();
        event.setSchemeId(1001);
        event.setTenantId(1);
        event.setSchemeName("Scheme-A");
        event.setStatus(1);
        event.setWorkStatus(4);
        when(dimSchemeRepository.findTopByTenantIdAndSchemeIdOrderByUpdatedAtDescCreatedAtDesc(1, 1001))
                .thenReturn(Optional.empty());

        service.upsertScheme(event);

        ArgumentCaptor<DimScheme> captor = ArgumentCaptor.forClass(DimScheme.class);
        verify(dimSchemeRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getWorkStatus()).isEqualTo(4);
    }

    @Test
    void updateIncludedWorkStatuses_setsArrayAndSaves() {
        IncludedWorkStatusesUpdatedEvent event = new IncludedWorkStatusesUpdatedEvent(
                "INCLUDED_WORK_STATUSES_UPDATED", 1, "MP", List.of(1, 4));
        DimTenant existing = DimTenant.builder().tenantId(1).stateCode("MP").title("MP").status(1).build();
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(existing));

        service.updateIncludedWorkStatuses(event);

        ArgumentCaptor<DimTenant> captor = ArgumentCaptor.forClass(DimTenant.class);
        verify(dimTenantRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getIncludedWorkStatuses()).containsExactly(1, 4);
    }

    @Test
    void updateIncludedWorkStatuses_tenantNotFound_throwsIllegalStateException() {
        IncludedWorkStatusesUpdatedEvent event = new IncludedWorkStatusesUpdatedEvent(
                "INCLUDED_WORK_STATUSES_UPDATED", 99, "XX", List.of(4));
        when(dimTenantRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateIncludedWorkStatuses(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99");
    }

    @Test
    void updateIncludedWorkStatuses_nationalTenantMissing_createsConfigRowInsteadOfThrowing() {
        // tenant-0 is a config-only singleton that no TENANT_CREATED event ever creates, so the
        // national default must be materialised on first write rather than dead-lettered. (Regression:
        // findById(0).orElseThrow silently DLQ'd every national update.)
        IncludedWorkStatusesUpdatedEvent event = new IncludedWorkStatusesUpdatedEvent(
                "INCLUDED_WORK_STATUSES_UPDATED", 0, "NATIONAL", List.of(4));
        when(dimTenantRepository.findById(0)).thenReturn(Optional.empty());

        service.updateIncludedWorkStatuses(event);

        ArgumentCaptor<DimTenant> captor = ArgumentCaptor.forClass(DimTenant.class);
        verify(dimTenantRepository, times(1)).save(captor.capture());
        DimTenant saved = captor.getValue();
        assertThat(saved.getTenantId()).isZero();
        assertThat(saved.getStateCode()).isEqualTo("NATIONAL");
        assertThat(saved.getTitle()).isEqualTo("NATIONAL");
        assertThat(saved.getStatus()).isEqualTo(1);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getIncludedWorkStatuses()).containsExactly(4);
    }

    @Test
    void updateIncludedWorkStatuses_opensScd2HistoryRow() {
        IncludedWorkStatusesUpdatedEvent event = new IncludedWorkStatusesUpdatedEvent(
                "INCLUDED_WORK_STATUSES_UPDATED", 1, "MP", List.of(1, 4));
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(
                DimTenant.builder().tenantId(1).stateCode("MP").title("MP").status(1).build()));
        when(dimTenantWorkStatusFilterRepository.findByTenantIdAndEffectiveToIsNull(1))
                .thenReturn(Optional.empty());

        service.updateIncludedWorkStatuses(event);

        ArgumentCaptor<DimTenantWorkStatusFilter> captor =
                ArgumentCaptor.forClass(DimTenantWorkStatusFilter.class);
        verify(dimTenantWorkStatusFilterRepository, times(1)).save(captor.capture());
        DimTenantWorkStatusFilter opened = captor.getValue();
        assertThat(opened.getTenantId()).isEqualTo(1);
        assertThat(opened.getEffectiveFrom()).isEqualTo(LocalDate.now());
        assertThat(opened.getEffectiveTo()).isNull();
        assertThat(opened.getIncludedWorkStatuses()).containsExactly(1, 4);
    }

    @Test
    void updateIncludedWorkStatuses_changedSet_closesOpenHistoryRowThenOpensNew() {
        IncludedWorkStatusesUpdatedEvent event = new IncludedWorkStatusesUpdatedEvent(
                "INCLUDED_WORK_STATUSES_UPDATED", 1, "MP", List.of(4));
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(
                DimTenant.builder().tenantId(1).stateCode("MP").title("MP").status(1).build()));
        DimTenantWorkStatusFilter open = DimTenantWorkStatusFilter.builder()
                .tenantId(1)
                .effectiveFrom(LocalDate.now().minusDays(30))
                .includedWorkStatuses(List.of(1, 4))
                .build();
        when(dimTenantWorkStatusFilterRepository.findByTenantIdAndEffectiveToIsNull(1))
                .thenReturn(Optional.of(open));

        service.updateIncludedWorkStatuses(event);

        assertThat(open.getEffectiveTo()).isEqualTo(LocalDate.now());
        verify(dimTenantWorkStatusFilterRepository, times(1)).saveAndFlush(open);
        ArgumentCaptor<DimTenantWorkStatusFilter> captor =
                ArgumentCaptor.forClass(DimTenantWorkStatusFilter.class);
        verify(dimTenantWorkStatusFilterRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getIncludedWorkStatuses()).containsExactly(4);
        assertThat(captor.getValue().getEffectiveTo()).isNull();
    }

    @Test
    void updateIncludedWorkStatuses_unchangedSet_keepsHistoryTimelineStable() {
        // Same set in a different order: the timeline must not churn.
        IncludedWorkStatusesUpdatedEvent event = new IncludedWorkStatusesUpdatedEvent(
                "INCLUDED_WORK_STATUSES_UPDATED", 1, "MP", List.of(4, 1));
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(
                DimTenant.builder().tenantId(1).stateCode("MP").title("MP").status(1).build()));
        DimTenantWorkStatusFilter open = DimTenantWorkStatusFilter.builder()
                .tenantId(1)
                .effectiveFrom(LocalDate.now().minusDays(30))
                .includedWorkStatuses(List.of(1, 4))
                .build();
        when(dimTenantWorkStatusFilterRepository.findByTenantIdAndEffectiveToIsNull(1))
                .thenReturn(Optional.of(open));

        service.updateIncludedWorkStatuses(event);

        assertThat(open.getEffectiveTo()).isNull();
        verify(dimTenantWorkStatusFilterRepository, times(0)).save(any(DimTenantWorkStatusFilter.class));
        verify(dimTenantWorkStatusFilterRepository, times(0)).saveAndFlush(any(DimTenantWorkStatusFilter.class));
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
    void updateWaterNorm_whenValueChanges_closesOpenRowAndOpensNewHistoryRow() {
        WaterNormUpdatedEvent event = new WaterNormUpdatedEvent("WATER_NORM_UPDATED", 1, "MP", 70);
        DimTenant existing = DimTenant.builder().tenantId(1).stateCode("MP").title("MP").status(1).build();
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(existing));

        DimTenantWaterNorm open = DimTenantWaterNorm.builder()
                .id(5L).tenantId(1).effectiveFrom(LocalDate.of(2026, 1, 1)).effectiveTo(null)
                .requiredLpcd(50).personCountPerHousehold(6)
                .overSupplyRangePercentage(15).underSupplyRangePercentage(20)
                .build();
        when(dimTenantWaterNormRepository.findByTenantIdAndEffectiveToIsNull(1))
                .thenReturn(Optional.of(open));

        service.updateWaterNorm(event);

        // Close is flushed first (saveAndFlush) so the unique open-row index never sees two open rows.
        ArgumentCaptor<DimTenantWaterNorm> closedCaptor = ArgumentCaptor.forClass(DimTenantWaterNorm.class);
        verify(dimTenantWaterNormRepository, times(1)).saveAndFlush(closedCaptor.capture());
        DimTenantWaterNorm closed = closedCaptor.getValue();
        assertThat(closed.getId()).isEqualTo(5L);
        assertThat(closed.getEffectiveTo()).isEqualTo(LocalDate.now());

        // New current row opened via save, carrying non-changed norm fields forward.
        ArgumentCaptor<DimTenantWaterNorm> openedCaptor = ArgumentCaptor.forClass(DimTenantWaterNorm.class);
        verify(dimTenantWaterNormRepository, times(1)).save(openedCaptor.capture());
        DimTenantWaterNorm opened = openedCaptor.getValue();
        assertThat(opened.getId()).isNull();
        assertThat(opened.getEffectiveFrom()).isEqualTo(LocalDate.now());
        assertThat(opened.getEffectiveTo()).isNull();
        assertThat(opened.getRequiredLpcd()).isEqualTo(70);
        assertThat(opened.getPersonCountPerHousehold()).isEqualTo(6);
        assertThat(opened.getOverSupplyRangePercentage()).isEqualTo(15);
        assertThat(opened.getUnderSupplyRangePercentage()).isEqualTo(20);
    }

    @Test
    void updateWaterNorm_whenValueUnchanged_leavesHistoryUntouched() {
        WaterNormUpdatedEvent event = new WaterNormUpdatedEvent("WATER_NORM_UPDATED", 1, "MP", 70);
        DimTenant existing = DimTenant.builder().tenantId(1).stateCode("MP").title("MP").status(1).build();
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(existing));

        DimTenantWaterNorm open = DimTenantWaterNorm.builder()
                .id(9L).tenantId(1).effectiveFrom(LocalDate.of(2026, 1, 1)).effectiveTo(null)
                .requiredLpcd(70).personCountPerHousehold(5)
                .build();
        when(dimTenantWaterNormRepository.findByTenantIdAndEffectiveToIsNull(1))
                .thenReturn(Optional.of(open));

        service.updateWaterNorm(event);

        verify(dimTenantRepository, times(1)).save(any(DimTenant.class));
        verify(dimTenantWaterNormRepository, never()).save(any());
        verify(dimTenantWaterNormRepository, never()).saveAndFlush(any());
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
    void updateWaterSupplyThreshold_recordsNormHistory() {
        WaterSupplyThresholdUpdatedEvent event =
                new WaterSupplyThresholdUpdatedEvent("WATER_SUPPLY_THRESHOLD_UPDATED", 1, "MP", 25, 35);
        DimTenant existing = DimTenant.builder().tenantId(1).stateCode("MP").title("MP").status(1).build();
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(existing));
        DimTenantWaterNorm open = DimTenantWaterNorm.builder()
                .id(7L).tenantId(1).effectiveFrom(LocalDate.of(2026, 1, 1)).effectiveTo(null)
                .requiredLpcd(55).personCountPerHousehold(5)
                .overSupplyRangePercentage(10).underSupplyRangePercentage(10)
                .build();
        when(dimTenantWaterNormRepository.findByTenantIdAndEffectiveToIsNull(1)).thenReturn(Optional.of(open));

        service.updateWaterSupplyThreshold(event);

        // Close the old norm row and open a new one with the updated thresholds,
        // carrying lpcd/persons forward.
        verify(dimTenantWaterNormRepository, times(1)).saveAndFlush(any());
        ArgumentCaptor<DimTenantWaterNorm> cap = ArgumentCaptor.forClass(DimTenantWaterNorm.class);
        verify(dimTenantWaterNormRepository, times(1)).save(cap.capture());
        DimTenantWaterNorm opened = cap.getValue();
        assertThat(opened.getEffectiveTo()).isNull();
        assertThat(opened.getUnderSupplyRangePercentage()).isEqualTo(25);
        assertThat(opened.getOverSupplyRangePercentage()).isEqualTo(35);
        assertThat(opened.getRequiredLpcd()).isEqualTo(55);
        assertThat(opened.getPersonCountPerHousehold()).isEqualTo(5);
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
    void replaceUserSchemeMappings_happyPath_deletesExistingAndInsertsNew() {
        UserSchemeMappingsReplacedEvent event = new UserSchemeMappingsReplacedEvent();
        event.setUserId(11);
        event.setTenantId(1);
        event.setSchemeIds(List.of(101, 102));
        event.setStatus(1);

        service.replaceUserSchemeMappings(event);

        // 1 DELETE + 2 INSERT = 3 total JdbcTemplate.update calls
        verify(jdbcTemplate, times(1)).update(anyString(), eq(1), eq(11));
        verify(jdbcTemplate, times(2)).update(anyString(), any(), eq(11), any(), eq(1), eq(1));
    }

    @Test
    void replaceUserSchemeMappings_nullUserId_skipsWithoutJdbcCall() {
        UserSchemeMappingsReplacedEvent event = new UserSchemeMappingsReplacedEvent();
        event.setUserId(null);
        event.setTenantId(1);
        event.setSchemeIds(List.of(101));

        service.replaceUserSchemeMappings(event);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void replaceUserSchemeMappings_nullTenantId_skipsWithoutJdbcCall() {
        UserSchemeMappingsReplacedEvent event = new UserSchemeMappingsReplacedEvent();
        event.setUserId(11);
        event.setTenantId(null);
        event.setSchemeIds(List.of(101));

        service.replaceUserSchemeMappings(event);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void replaceUserSchemeMappings_emptySchemeIds_onlyDeletesWithoutInsert() {
        UserSchemeMappingsReplacedEvent event = new UserSchemeMappingsReplacedEvent();
        event.setUserId(11);
        event.setTenantId(1);
        event.setSchemeIds(List.of());
        event.setStatus(1);

        service.replaceUserSchemeMappings(event);

        verify(jdbcTemplate, times(1)).update(anyString(), eq(1), eq(11));
        verify(jdbcTemplate, never()).update(anyString(), any(), eq(11), any(), any(), any());
    }

    @Test
    void replaceUserSchemeMappings_nullStatus_defaultsToStatusOne() {
        UserSchemeMappingsReplacedEvent event = new UserSchemeMappingsReplacedEvent();
        event.setUserId(11);
        event.setTenantId(1);
        event.setSchemeIds(List.of(101));
        event.setStatus(null);

        service.replaceUserSchemeMappings(event);

        verify(jdbcTemplate, times(1)).update(anyString(), eq(1), eq(11));
        verify(jdbcTemplate, times(1)).update(anyString(), any(), eq(11), eq(101), eq(1), eq(1));
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
