package org.arghyam.jalsoochak.telemetry.config;

import org.arghyam.jalsoochak.telemetry.controller.staff.StaffReadingCorrectionController;
import org.arghyam.jalsoochak.telemetry.dto.response.UpdateYesterdayFinalReadingBySchemeResponse;
import org.arghyam.jalsoochak.telemetry.service.StaffCaller;
import org.arghyam.jalsoochak.telemetry.service.TelemetrySchemeReadingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The staff chain end to end: bearer token → {@link JwtAuthConverter} → the SO/SDO rule →
 * {@link StaffReadingCorrectionController} → the caller handed to the service.
 *
 * <p>A minimal web context rather than {@code @SpringBootTest}, for the reason given in
 * {@code MultiFormatReadingControllerTest}. Tokens are decoded by a stub {@link JwtDecoder}, so the
 * real converter and authorisation rule run against fixed claims without reaching Keycloak.
 */
@SpringJUnitWebConfig(SecurityConfigTest.StaffWebContext.class)
@DisplayName("SecurityConfig — the SO/SDO staff chain")
class SecurityConfigTest {

    private static final String ROUTE = "/api/v1/telemetry/staff/schemes/7/yesterday-final-reading";
    private static final String OFFICER_UUID = "5b0f8f7e-2a4c-4d8e-9a51-0c6f3c1d2e7a";
    private static final StaffCaller OFFICER = new StaffCaller("AS", OFFICER_UUID);
    private static final String BODY = "{\"reading\": 600}";

    /** Token value → claims the stub decoder returns. Anything else fails to decode. */
    private static final Map<String, Map<String, Object>> TOKENS = Map.of(
            "so", Map.of("user_type", "SECTION_OFFICER", "tenant_state_code", "AS"),
            "sdo", Map.of("user_type", "SUB_DIVISIONAL_OFFICER", "tenant_state_code", "AS"),
            "operator", Map.of("user_type", "PUMP_OPERATOR", "tenant_state_code", "AS"),
            "state-admin", Map.of("tenant_state_code", "AS",
                    "realm_access", Map.of("roles", List.of("STATE_ADMIN"))));

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, StaffReadingCorrectionController.class})
    static class StaffWebContext {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                Map<String, Object> claims = TOKENS.get(token);
                if (claims == null) {
                    throw new BadJwtException("unknown test token");
                }
                Jwt.Builder builder = Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject(OFFICER_UUID)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300));
                claims.forEach(builder::claim);
                return builder.build();
            };
        }
    }

    @MockBean
    private TelemetrySchemeReadingService service;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy filterChainProxy;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(service.updateYesterdayFinalReadingBySchemeId(any(), any(), any(), any()))
                .thenReturn(UpdateYesterdayFinalReadingBySchemeResponse.builder()
                        .success(true)
                        .schemeId(7L)
                        .readingDate("2026-03-01")
                        .finalReading(new BigDecimal("600"))
                        .message("Final reading updated successfully.")
                        .build());
    }

    private static MockHttpServletRequestBuilder correction(String token) {
        MockHttpServletRequestBuilder request = patch(ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    @Test
    @DisplayName("a request with no token is refused with a bearer challenge")
    void rejectsARequestWithNoToken() throws Exception {
        mockMvc.perform(correction(null))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a token that fails to decode is refused")
    void rejectsAnUndecodableToken() throws Exception {
        mockMvc.perform(correction("forged"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"operator", "state-admin"})
    @DisplayName("a valid token that is not a section or sub-divisional officer's is forbidden")
    void forbidsOtherUserTypes(String token) throws Exception {
        mockMvc.perform(correction(token))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a section officer's correction reaches the service as the token's tenant and subject")
    void admitsASectionOfficer() throws Exception {
        MvcResult result = mockMvc.perform(correction("so").header("X-Tenant-Code", "MP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.readingDate").value("2026-03-01"))
                .andReturn();

        // X-Tenant-Code names another tenant; the token's claim wins. No CSRF token was sent, and no
        // session is created.
        verify(service).updateYesterdayFinalReadingBySchemeId(7L, new BigDecimal("600"), null, OFFICER);
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("a sub-divisional officer is admitted, and the optional date reaches the service")
    void admitsASubDivisionalOfficerWithADate() throws Exception {
        mockMvc.perform(correction("sdo").param("date", "2026-03-01"))
                .andExpect(status().isOk());

        verify(service).updateYesterdayFinalReadingBySchemeId(
                7L, new BigDecimal("600"), LocalDate.of(2026, 3, 1), OFFICER);
    }

    @Test
    @DisplayName("a refusal from the service keeps its status, in the response envelope")
    void answersAServiceRefusalInTheEnvelope() throws Exception {
        when(service.updateYesterdayFinalReadingBySchemeId(any(), any(), any(), eq(OFFICER)))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized for this scheme"));

        mockMvc.perform(correction("so"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.schemeId").value(7))
                .andExpect(jsonPath("$.message").value("Not authorized for this scheme"));
    }

    @Test
    @DisplayName("an unexpected failure is a 500 that names no internals")
    void answersAnUnexpectedFailureWithA500() throws Exception {
        when(service.updateYesterdayFinalReadingBySchemeId(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("connection reset"));

        mockMvc.perform(correction("so"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Internal error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"reading\": 0}", "{}"})
    @DisplayName("an invalid reading is a 400 in the envelope and never reaches the service")
    void rejectsAnInvalidReading(String body) throws Exception {
        mockMvc.perform(correction("so").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a malformed body is a 400 in the envelope")
    void rejectsAMalformedBody() throws Exception {
        mockMvc.perform(correction("so").content("{\"reading\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("paths outside /staff are not challenged by the chain")
    void leavesOtherPathsAlone() throws Exception {
        // Nothing is mapped at these paths in this context, so reaching the dispatcher shows as 404.
        // A 401 would mean the chain had claimed them, and the webhook, API-key and probe routes with
        // them.
        mockMvc.perform(patch("/api/v1/telemetry/schemes/7/yesterday-final-reading")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/telemetry/readings")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the service declares exactly one chain, and it applies no CORS")
    void declaresOneChainWithoutCors() {
        // CORS has to be disabled explicitly: Spring Security turns it on by itself when exactly one
        // CorsConfigurationSource bean exists, and Spring MVC's HandlerMappingIntrospector is one.
        List<SecurityFilterChain> chains = filterChainProxy.getFilterChains();

        assertThat(chains).hasSize(1);
        assertThat(chains.get(0).getFilters()).noneMatch(CorsFilter.class::isInstance);
    }
}
