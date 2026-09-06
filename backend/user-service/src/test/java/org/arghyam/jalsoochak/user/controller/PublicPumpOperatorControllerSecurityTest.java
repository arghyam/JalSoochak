package org.arghyam.jalsoochak.user.controller;

import org.arghyam.jalsoochak.user.config.JwtAuthConverter;
import org.arghyam.jalsoochak.user.config.SecurityConfig;
import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.arghyam.jalsoochak.user.service.PublicPumpOperatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the anonymous surface of the pump-operator tree.
 *
 * <p>The whole tree used to be {@code permitAll}, which exposed operator PII, person-scoped reads
 * and a tenant-wide compliance list to unauthenticated callers. Exactly three routes are public now
 * — the ones the anonymous village dashboard calls — and every other route requires a token.
 *
 * <p>These tests run the real filter chain, so a future {@code permitAll} that widens the anonymous
 * surface fails here rather than in an audit.
 */
@WebMvcTest(PublicPumpOperatorController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@DisplayName("PublicPumpOperatorController Security Tests")
class PublicPumpOperatorControllerSecurityTest {

    private static final String OPERATOR_UUID = "3f1a9c22-5b7e-4d38-9a10-8c4b2e6f0d71";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppProperties appProperties;

    @MockBean
    private PublicPumpOperatorService publicPumpOperatorService;

    @MockBean
    private PersonSchemeService personSchemeService;

    @Nested
    @DisplayName("Anonymous access is allowed on exactly the village-dashboard routes")
    class PublicRoutes {

        @Test
        @DisplayName("GET /pump-operators/by-uuid/{uuid} is reachable without a token")
        void byUuidIsPublic() throws Exception {
            when(publicPumpOperatorService.getPumpOperatorDetailsByUuid(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(PumpOperatorDetailsDTO.builder().id(1L).uuid(OPERATOR_UUID).build());

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-uuid/" + OPERATOR_UUID)
                            .param("tenantCode", "mp"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /pump-operators/by-scheme is reachable without a token")
        void bySchemeIsPublic() throws Exception {
            when(publicPumpOperatorService.listPumpOperatorsByScheme(anyString(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme")
                            .param("tenantCode", "mp").param("schemeId", "5"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /pump-operators/by-scheme/reading-compliance is reachable without a token")
        void bySchemeComplianceIsPublic() throws Exception {
            when(publicPumpOperatorService.listPumpOperatorsBySchemeWithCompliance(
                    anyString(), anyLong(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(PageResponseDTO.of(List.of(), 0L, 0, 20));

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance")
                            .param("tenantCode", "mp").param("schemeId", "5"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Every other route requires authentication")
    class AuthenticatedRoutes {

        /**
         * The routes the audit walked, plus the person- and scheme-scoped reads. All are called only
         * by the Section Officer console, which already sends a bearer token.
         */
        static Stream<String> authenticatedEndpoints() {
            return Stream.of(
                    // Sequential-id detail route — the enumeration vector in the audit report.
                    "/api/v1/pumpoperator/pump-operators/1",
                    // The endpoint the report used to prove 20,462 records were reachable.
                    "/api/v1/pumpoperator/pump-operators/reading-compliance",
                    "/api/v1/pumpoperator/pump-operators/1/reading-compliance",
                    "/api/v1/pumpoperator/pump-operators/1/details-with-compliance",
                    "/api/v1/pumpoperator/pump-operators/1/readings",
                    "/api/v1/pumpoperator/person/10/schemes",
                    "/api/v1/pumpoperator/person/10/schemes/count",
                    "/api/v1/pumpoperator/person/10/pump-operators",
                    "/api/v1/pumpoperator/schemes/5/details",
                    "/api/v1/pumpoperator/schemes/5/reading-submissions"
            );
        }

        @ParameterizedTest(name = "{0} returns 401 without a token")
        @MethodSource("authenticatedEndpoints")
        @DisplayName("returns 401 when unauthenticated")
        void returns401(String path) throws Exception {
            mockMvc.perform(get(path).param("tenantCode", "mp"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("the numeric-id detail route stays closed even for a well-formed id")
        void numericIdRouteIsNotAnEnumerationVector() throws Exception {
            when(publicPumpOperatorService.getPumpOperatorDetails(anyString(), anyLong(), any(), any(), any()))
                    .thenReturn(PumpOperatorDetailsDTO.builder().id(21315L).build());

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/21315")
                            .param("tenantCode", "as"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("compliance for a single operator stays closed")
        void singleOperatorComplianceIsClosed() throws Exception {
            when(publicPumpOperatorService.getReadingCompliance(anyString(), anyLong()))
                    .thenReturn(PumpOperatorReadingComplianceDTO.builder().build());

            mockMvc.perform(get("/api/v1/pumpoperator/pump-operators/7/reading-compliance")
                            .param("tenantCode", "as"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
