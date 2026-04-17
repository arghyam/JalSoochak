package org.arghyam.jalsoochak.user.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SecurityConfigTest {

    @Mock
    private JwtAuthConverter jwtAuthConverter;

    @Mock
    private Environment environment;

    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig(jwtAuthConverter, environment);
    }

    private void setAllowedOrigins(String origins) throws Exception {
        var field = SecurityConfig.class.getDeclaredField("allowedOrigins");
        field.setAccessible(true);
        field.set(securityConfig, origins);
    }

    @Test
    void testCorsConfigurationSource_WithValidOrigins() throws Exception {
        setAllowedOrigins("http://localhost:3000,https://example.com");

        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        assertNotNull(source);

        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        CorsConfiguration cfg = source.getCorsConfiguration(request);

        assertNotNull(cfg);
        assertEquals(List.of("http://localhost:3000", "https://example.com"), cfg.getAllowedOrigins());
        assertTrue(cfg.getAllowCredentials());
        assertTrue(cfg.getAllowedMethods().contains("GET"));
        assertTrue(cfg.getAllowedMethods().contains("POST"));
        assertTrue(cfg.getAllowedMethods().contains("PUT"));
        assertTrue(cfg.getAllowedMethods().contains("DELETE"));
        assertTrue(cfg.getAllowedMethods().contains("OPTIONS"));
    }

    @Test
    void testCorsConfigurationSource_WithEmptyOrigins() throws Exception {
        setAllowedOrigins("");

        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        assertNotNull(source);

        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        CorsConfiguration cfg = source.getCorsConfiguration(request);

        assertNotNull(cfg);
        assertTrue(cfg.getAllowedOrigins().isEmpty());
    }

    @Test
    void testCorsConfigurationSource_WithNullOrigins() throws Exception {
        setAllowedOrigins(null);

        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        assertNotNull(source);

        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        CorsConfiguration cfg = source.getCorsConfiguration(request);

        assertNotNull(cfg);
        assertTrue(cfg.getAllowedOrigins().isEmpty());
    }

    @Test
    void testCorsConfigurationSource_WithBlankOrigins() throws Exception {
        setAllowedOrigins("   , ,  ");

        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        assertNotNull(source);

        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        CorsConfiguration cfg = source.getCorsConfiguration(request);

        assertNotNull(cfg);
        assertTrue(cfg.getAllowedOrigins().isEmpty());
    }
}
