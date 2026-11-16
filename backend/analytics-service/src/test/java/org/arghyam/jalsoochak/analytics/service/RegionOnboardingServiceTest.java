package org.arghyam.jalsoochak.analytics.service;

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

    @Test
    void requireOnboarded_passesAnOnboardedRegion() {
        when(repository.findLevel(TENANT_ID, RegionHierarchy.DEPARTMENT, REGION_ID)).thenReturn(Optional.of(4));
        when(repository.hasSchemeInOnboardedDistrict(TENANT_ID, RegionHierarchy.DEPARTMENT, 4, REGION_ID))
                .thenReturn(true);

        assertThatCode(() -> service(true).requireOnboarded(TENANT_ID, RegionHierarchy.DEPARTMENT, REGION_ID))
                .doesNotThrowAnyException();
    }
}
