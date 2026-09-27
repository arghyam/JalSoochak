package org.arghyam.jalsoochak.user.controller.operator;

import org.arghyam.jalsoochak.user.config.JwtAuthConverter;
import org.arghyam.jalsoochak.user.config.PumpOperatorAccessGuard;
import org.arghyam.jalsoochak.user.config.SecurityConfig;
import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins that a scheme's details and reading submissions need a token. Neither is on the anonymous
 * village-dashboard surface that {@code PumpOperatorQueryControllerSecurityTest} pins.
 */
@WebMvcTest(SchemeReadingQueryController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost")
@DisplayName("SchemeReadingQueryController Security Tests")
class SchemeReadingQueryControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppProperties appProperties;

    @MockBean
    private PersonSchemeService personSchemeService;

    @MockBean
    private PumpOperatorAccessGuard accessGuard;

    @Nested
    @DisplayName("Every route requires authentication")
    class AuthenticatedRoutes {

        /**
         * Called only by the Section Officer console, which already sends a bearer token.
         */
        static Stream<String> authenticatedEndpoints() {
            return Stream.of(
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
    }
}
