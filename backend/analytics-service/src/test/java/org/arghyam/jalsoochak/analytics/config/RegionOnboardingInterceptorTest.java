package org.arghyam.jalsoochak.analytics.config;

import org.arghyam.jalsoochak.analytics.enums.RegionHierarchy;
import org.arghyam.jalsoochak.analytics.exception.RegionNotOnboardedException;
import org.arghyam.jalsoochak.analytics.service.RegionOnboardingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RegionOnboardingInterceptorTest {

    @Mock
    private RegionOnboardingService regionOnboardingService;

    private RegionOnboardingInterceptor interceptor;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        interceptor = new RegionOnboardingInterceptor(regionOnboardingService);
        request = new MockHttpServletRequest("GET", "/api/v1/analytics/scheme-regularity/average");
    }

    private boolean preHandle() {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @ParameterizedTest
    @CsvSource({
            "lgd_id, LGD",
            "parent_lgd_id, LGD",
            "department_id, DEPARTMENT",
            "parent_department_id, DEPARTMENT"
    })
    void eachRegionParam_isCheckedInItsHierarchy(String param, RegionHierarchy hierarchy) {
        request.addParameter("tenant_id", "3");
        request.addParameter(param, "12");

        assertThat(preHandle()).isTrue();
        verify(regionOnboardingService).requireOnboarded(3, hierarchy, 12);
    }

    @Test
    void notOnboardedRegion_propagatesTheException() {
        request.addParameter("tenant_id", "3");
        request.addParameter("parent_lgd_id", "12");
        doThrow(new RegionNotOnboardedException(3, RegionHierarchy.LGD, 12))
                .when(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 12);

        assertThatThrownBy(this::preHandle).isInstanceOf(RegionNotOnboardedException.class);
    }

    @Test
    void everyRegionParamPresent_isChecked() {
        request.addParameter("tenant_id", "3");
        request.addParameter("lgd_id", "12");
        request.addParameter("parent_department_id", "40");

        assertThat(preHandle()).isTrue();
        verify(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 12);
        verify(regionOnboardingService).requireOnboarded(3, RegionHierarchy.DEPARTMENT, 40);
        verifyNoMoreInteractions(regionOnboardingService);
    }

    @Test
    void repeatedRegionParam_checksEveryValue() {
        request.addParameter("tenant_id", "3");
        request.addParameter("parent_lgd_id", "12", "15");

        assertThat(preHandle()).isTrue();
        verify(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 12);
        verify(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 15);
    }

    /** Spring binds these to 12, so the guard must read them as 12 too, or they would slip past it. */
    @ParameterizedTest
    @ValueSource(strings = {" 12", "12 ", "+12", "0xC", "#C", "012"})
    void idSpelledAnyWayTheControllerAccepts_isStillChecked(String value) {
        request.addParameter("tenant_id", " 3 ");
        request.addParameter("parent_lgd_id", value);

        assertThat(preHandle()).isTrue();
        verify(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 12);
    }

    /** {@code @RequestParam} falls back to {@code name[]} when {@code name} is absent, so the guard must too. */
    @ParameterizedTest
    @CsvSource({
            "lgd_id[], LGD",
            "parent_lgd_id[], LGD",
            "department_id[], DEPARTMENT",
            "parent_department_id[], DEPARTMENT"
    })
    void bracketedRegionParam_isChecked(String param, RegionHierarchy hierarchy) {
        request.addParameter("tenant_id", "3");
        request.addParameter(param, "12");

        assertThat(preHandle()).isTrue();
        verify(regionOnboardingService).requireOnboarded(3, hierarchy, 12);
    }

    @Test
    void bracketedTenantId_isUsed() {
        request.addParameter("tenant_id[]", "3");
        request.addParameter("parent_lgd_id", "12");

        assertThat(preHandle()).isTrue();
        verify(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 12);
    }

    /** As in {@code @RequestParam}, the bracketed alias is read only when the exact name is absent. */
    @Test
    void exactName_shadowsItsBracketedAlias() {
        request.addParameter("tenant_id", "3");
        request.addParameter("tenant_id[]", "4");
        request.addParameter("parent_lgd_id", "12");
        request.addParameter("parent_lgd_id[]", "15");

        assertThat(preHandle()).isTrue();
        verify(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 12);
        verifyNoMoreInteractions(regionOnboardingService);
    }

    @Test
    void missingTenantId_isLeftToTheController() {
        request.addParameter("parent_lgd_id", "12");

        assertThat(preHandle()).isTrue();
        verifyNoInteractions(regionOnboardingService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0", "-3", "3.5", "99999999999"})
    void invalidTenantId_isLeftToTheController(String tenantId) {
        request.addParameter("tenant_id", tenantId);
        request.addParameter("parent_lgd_id", "12");

        assertThat(preHandle()).isTrue();
        verifyNoInteractions(regionOnboardingService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0", "-12", "12,15", "99999999999"})
    void malformedRegionId_isLeftToTheController(String regionId) {
        request.addParameter("tenant_id", "3");
        request.addParameter("lgd_id", regionId);

        assertThat(preHandle()).isTrue();
        verifyNoInteractions(regionOnboardingService);
    }

    @Test
    void noRegionParam_passesWithoutACheck() {
        request.addParameter("tenant_id", "3");

        assertThat(preHandle()).isTrue();
        verifyNoInteractions(regionOnboardingService);
    }
}
