package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.response.NationalOnboardedDistrictsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.OnboardedRegionsResponse;
import org.arghyam.jalsoochak.analytics.enums.RegionHierarchy;
import org.arghyam.jalsoochak.analytics.exception.RegionNotOnboardedException;
import org.arghyam.jalsoochak.analytics.repository.RegionOnboardingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegionOnboardingServiceTest {

    private static final int TENANT_ID = 3;
    private static final int REGION_ID = 12;

    @Mock
    private RegionOnboardingRepository repository;

    private RegionOnboardingService service(boolean phasedRollout) {
        return new RegionOnboardingService(repository, phasedRollout);
    }

    @ParameterizedTest
    @EnumSource(RegionHierarchy.class)
    void switchOff_everyRegionIsOnboarded_withoutAQuery(RegionHierarchy hierarchy) {
        RegionOnboardingService service = service(false);

        assertThat(service.isOnboarded(TENANT_ID, hierarchy, REGION_ID)).isTrue();
        assertThatCode(() -> service.requireOnboarded(TENANT_ID, hierarchy, REGION_ID)).doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }

    @Test
    void regionTheTenantDoesNotHave_passesThroughForTheEndpointToReject() {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.LGD, REGION_ID)).thenReturn(Optional.empty());

        assertThat(service(true).isOnboarded(TENANT_ID, RegionHierarchy.LGD, REGION_ID)).isTrue();
        verify(repository, never()).hasSchemeInOnboardedDistrict(anyInt(), any(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void stateLevel_isAlwaysOnboarded(int level) {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.DEPARTMENT, REGION_ID)).thenReturn(Optional.of(level));

        assertThat(service(true).isOnboarded(TENANT_ID, RegionHierarchy.DEPARTMENT, REGION_ID)).isTrue();
        verify(repository, never()).hasSchemeInOnboardedDistrict(anyInt(), any(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void belowTheState_isOnboardedOnlyWithASchemeInAnOnboardedDistrict(boolean hasScheme) {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.LGD, REGION_ID)).thenReturn(Optional.of(3));
        when(repository.hasSchemeInOnboardedDistrict(TENANT_ID, RegionHierarchy.LGD, 3, REGION_ID))
                .thenReturn(hasScheme);

        assertThat(service(true).isOnboarded(TENANT_ID, RegionHierarchy.LGD, REGION_ID)).isEqualTo(hasScheme);
    }

    @Test
    void belowTheDeepestSchemeLevel_isNotOnboarded_sinceNoSchemeCanSitThere() {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.LGD, REGION_ID))
                .thenReturn(Optional.of(RegionOnboardingRepository.MAX_LEVEL + 1));

        assertThat(service(true).isOnboarded(TENANT_ID, RegionHierarchy.LGD, REGION_ID)).isFalse();
        verify(repository, never()).hasSchemeInOnboardedDistrict(anyInt(), any(), anyInt(), anyInt());
    }

    @Test
    void requireOnboarded_throwsForARegionThatIsNotOnboarded() {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.LGD, REGION_ID)).thenReturn(Optional.of(2));
        when(repository.hasSchemeInOnboardedDistrict(TENANT_ID, RegionHierarchy.LGD, 2, REGION_ID)).thenReturn(false);

        assertThatThrownBy(() -> service(true).requireOnboarded(TENANT_ID, RegionHierarchy.LGD, REGION_ID))
                .isInstanceOf(RegionNotOnboardedException.class)
                .hasMessageContaining(String.valueOf(TENANT_ID))
                .hasMessageContaining(String.valueOf(REGION_ID));
    }

    @ParameterizedTest
    @EnumSource(RegionHierarchy.class)
    void onboardedChildren_switchOff_saysSoWithNullIds_withoutAQuery(RegionHierarchy hierarchy) {
        OnboardedRegionsResponse response = service(false).onboardedChildren(TENANT_ID, hierarchy, REGION_ID);

        assertThat(response.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(response.isPhasedRollout()).isFalse();
        assertThat(response.getOnboardedChildIds()).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void onboardedChildren_lgdParent_listsTheChildrenOneLevelDown() {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.LGD, REGION_ID)).thenReturn(Optional.of(1));
        when(repository.findOnboardedChildIds(TENANT_ID, RegionHierarchy.LGD, 1, REGION_ID))
                .thenReturn(List.of(20, 25));

        OnboardedRegionsResponse response = service(true).onboardedChildren(TENANT_ID, RegionHierarchy.LGD, REGION_ID);

        assertThat(response.isPhasedRollout()).isTrue();
        assertThat(response.getParentLgdId()).isEqualTo(REGION_ID);
        assertThat(response.getParentDepartmentId()).isNull();
        assertThat(response.getOnboardedChildIds()).containsExactly(20, 25);
    }

    @Test
    void onboardedChildren_departmentParent_namesItAsTheDepartmentParent() {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.DEPARTMENT, REGION_ID)).thenReturn(Optional.of(3));
        when(repository.findOnboardedChildIds(TENANT_ID, RegionHierarchy.DEPARTMENT, 3, REGION_ID))
                .thenReturn(List.of());

        OnboardedRegionsResponse response =
                service(true).onboardedChildren(TENANT_ID, RegionHierarchy.DEPARTMENT, REGION_ID);

        assertThat(response.getParentLgdId()).isNull();
        assertThat(response.getParentDepartmentId()).isEqualTo(REGION_ID);
        assertThat(response.getOnboardedChildIds()).isEmpty();
    }

    @Test
    void onboardedChildren_parentTheTenantDoesNotHave_isABadRequest() {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.LGD, REGION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(true).onboardedChildren(TENANT_ID, RegionHierarchy.LGD, REGION_ID))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findOnboardedChildIds(anyInt(), any(), anyInt(), anyInt());
    }

    @Test
    void onboardedDistrictsByTenant_switchOff_saysSoWithNullTenants_withoutAQuery() {
        NationalOnboardedDistrictsResponse response = service(false).onboardedDistrictsByTenant();

        assertThat(response.isPhasedRollout()).isFalse();
        assertThat(response.getTenants()).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void onboardedDistrictsByTenant_listsEachTenantInTheRepositorysOrder() {
        Map<Integer, List<Integer>> byTenant = new LinkedHashMap<>();
        byTenant.put(3, List.of(12, 15));
        byTenant.put(7, List.of(40));
        when(repository.findOnboardedDistrictIdsByTenant()).thenReturn(byTenant);

        NationalOnboardedDistrictsResponse response = service(true).onboardedDistrictsByTenant();

        assertThat(response.isPhasedRollout()).isTrue();
        assertThat(response.getTenants()).containsExactly(
                new NationalOnboardedDistrictsResponse.TenantOnboardedDistricts(3, List.of(12, 15)),
                new NationalOnboardedDistrictsResponse.TenantOnboardedDistricts(7, List.of(40)));
    }

    @Test
    void onboardedDistrictsByTenant_noneOnboarded_isAnEmptyListNotNull() {
        when(repository.findOnboardedDistrictIdsByTenant()).thenReturn(Map.of());

        NationalOnboardedDistrictsResponse response = service(true).onboardedDistrictsByTenant();

        assertThat(response.isPhasedRollout()).isTrue();
        assertThat(response.getTenants()).isEmpty();
    }

    @Test
    void requireOnboarded_passesAnOnboardedRegion() {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.DEPARTMENT, REGION_ID)).thenReturn(Optional.of(4));
        when(repository.hasSchemeInOnboardedDistrict(TENANT_ID, RegionHierarchy.DEPARTMENT, 4, REGION_ID))
                .thenReturn(true);

        assertThatCode(() -> service(true).requireOnboarded(TENANT_ID, RegionHierarchy.DEPARTMENT, REGION_ID))
                .doesNotThrowAnyException();
    }
}
