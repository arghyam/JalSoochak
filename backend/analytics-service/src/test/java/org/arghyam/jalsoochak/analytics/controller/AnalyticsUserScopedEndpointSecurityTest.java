package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.config.JwtAuthConverter;
import org.arghyam.jalsoochak.analytics.config.SecurityConfig;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.service.AnomalyQueryService;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.EscalationQueryService;
import org.arghyam.jalsoochak.analytics.service.OperatorAttendanceQueryService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.arghyam.jalsoochak.analytics.service.UserAlertTotalsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Enforces the role check on the two user-scoped analytics endpoints.
 *
 * <p>{@code AnalyticsSchemeReportingControllerTest} runs with {@code addFilters = false} and does
 * not import {@link SecurityConfig}, so method security is inert there and a missing
 * {@code @PreAuthorize} would go unnoticed. This slice imports the real config, which carries
 * {@code @EnableMethodSecurity}, so the annotation is actually evaluated.
 *
 * <p>The SA report covered only the anonymous case on {@code /continuous-schemes/user}. Both
 * endpoints took {@code tenant_id} and {@code user_id} from the query string, so authenticating
 * them alone would have left any signed-in user — a pump operator, or an officer in another state —
 * able to read someone else's figures. The role check is the second half of that fix.
 */
@WebMvcTest(controllers = AnalyticsSchemeReportingController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "spring.profiles.active=test")
@DisplayName("User-scoped analytics endpoint security")
class AnalyticsUserScopedEndpointSecurityTest {

    private static final String CONTINUOUS = "/api/v1/analytics/continuous-schemes/user";
    private static final String OFFICER_DASHBOARD = "/api/v1/analytics/officer/dashboard";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;
    @MockBean
    private FactSchemePerformanceRepository schemePerformanceRepository;
    @MockBean
    private SchemeRegularityService schemeRegularityService;
    @MockBean
    private EscalationQueryService escalationQueryService;
    @MockBean
    private AnomalyQueryService anomalyQueryService;
    @MockBean
    private OperatorAttendanceQueryService operatorAttendanceQueryService;
    @MockBean
    private UserAlertTotalsService userAlertTotalsService;
    @MockBean
    private AuthenticatedRequestContextService authenticatedRequestContextService;
    @MockBean
    private DimUserRepository dimUserRepository;
    @MockBean
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    @ParameterizedTest(name = "{0} is not anonymous")
    @ValueSource(strings = {CONTINUOUS, OFFICER_DASHBOARD})
    @DisplayName("rejects an anonymous caller — the reported finding")
    void anonymousIsRejected(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} rejects a non-officer token")
    @ValueSource(strings = {CONTINUOUS, OFFICER_DASHBOARD})
    @DisplayName("rejects an authenticated caller without an officer role")
    void authenticatedNonOfficerIsForbidden(String path) throws Exception {
        // A pump operator holds a perfectly valid token. Authentication alone must not be enough.
        mockMvc.perform(get(path).with(jwt().authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("USER_TYPE_PUMP_OPERATOR"))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} admits a sub-divisional officer")
    @ValueSource(strings = {CONTINUOUS, OFFICER_DASHBOARD})
    @DisplayName("admits a sub-divisional officer past the role check")
    void subDivisionalOfficerIsAdmitted(String path) throws Exception {
        // extractAuthenticatedUserRef is a mock returning null, so the handler answers 400 for a
        // missing tenant. Anything other than 401/403 proves the role check let this caller in,
        // which is what this test is about.
        mockMvc.perform(get(path)
                        // Explicit dates keep the mocked default-window provider out of the path,
                        // so a 400 can only come from the tenant check this test is aiming at.
                        .param("start_date", "2026-01-01")
                        .param("end_date", "2026-01-31")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "USER_TYPE_SUB_DIVISIONAL_OFFICER"))))
                .andExpect(status().isBadRequest());
    }
}
