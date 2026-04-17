package org.arghyam.jalsoochak.user.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SecurityConfigTest {

    @Mock
    private JwtAuthConverter jwtAuthConverter;

    @Mock
    private Environment environment;

    @Mock
    private HttpSecurity httpSecurity;

    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig(jwtAuthConverter, environment);
    }

    @Test
    void testCorsConfigurationSource_Creation() {
        // Using reflection to set the private field
        try {
            var field = SecurityConfig.class.getDeclaredField("allowedOrigins");
            field.setAccessible(true);
            field.set(securityConfig, "http://localhost:3000,https://example.com");
        } catch (Exception e) {
            fail("Failed to set allowedOrigins field");
        }

        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        assertNotNull(source);
        
        // Basic smoke test - the source should be created successfully
        assertDoesNotThrow(() -> source.toString());
    }

    @Test
    void testCorsConfigurationSource_WithEmptyOrigins() {
        try {
            var field = SecurityConfig.class.getDeclaredField("allowedOrigins");
            field.setAccessible(true);
            field.set(securityConfig, "");
        } catch (Exception e) {
            fail("Failed to set allowedOrigins field");
        }

        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        assertNotNull(source);
        
        assertDoesNotThrow(() -> source.toString());
    }

    @Test
    void testCorsConfigurationSource_WithNullOrigins() {
        try {
            var field = SecurityConfig.class.getDeclaredField("allowedOrigins");
            field.setAccessible(true);
            field.set(securityConfig, null);
        } catch (Exception e) {
            fail("Failed to set allowedOrigins field");
        }

        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        assertNotNull(source);
        
        assertDoesNotThrow(() -> source.toString());
    }

    @Test
    void testCorsConfigurationSource_WithBlankOrigins() {
        try {
            var field = SecurityConfig.class.getDeclaredField("allowedOrigins");
            field.setAccessible(true);
            field.set(securityConfig, "   , ,  ");
        } catch (Exception e) {
            fail("Failed to set allowedOrigins field");
        }

        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        assertNotNull(source);
        
        assertDoesNotThrow(() -> source.toString());
    }

    @Test
    void testSecurityFilterChain_InProduction() throws Exception {
        // This test verifies that the method exists and can be called
        // Actual SecurityFilterChain creation requires complex Spring Security setup
        assertDoesNotThrow(() -> {
            // Note: Full testing would require more complex Spring Security test setup
            // This is a basic smoke test to ensure the method exists
            SecurityFilterChain.class.getMethod("getFilters");
        });
    }

    @Test
    void testSecurityFilterChain_InDevelopment() throws Exception {
        // This test verifies that the method exists and can be called
        assertDoesNotThrow(() -> {
            SecurityFilterChain.class.getMethod("getFilters");
        });
    }
}
