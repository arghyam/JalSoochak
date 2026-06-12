package org.arghyam.jalsoochak.user.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class KeycloakProviderTest {

    private KeycloakProvider keycloakProvider;

    @BeforeEach
    void setUp() {
        keycloakProvider = new KeycloakProvider(
            "http://localhost:8080/auth",
            "test-realm",
            "test-client",
            "test-secret",
            "admin-client",
            "admin-secret"
        );
    }

    @Test
    void testConstructorAndGetters() {
        assertNotNull(keycloakProvider);
        assertEquals("http://localhost:8080/auth", keycloakProvider.getServerURL());
        assertEquals("test-realm", keycloakProvider.getRealm());
        assertNotNull(keycloakProvider.getAdminInstance());
        assertNotNull(keycloakProvider.getLoginInstance());
    }

    @Test
    void testAdminInstanceCreationLifecycleAndCachingOnly() {
        Keycloak adminInstance = keycloakProvider.getAdminInstance();
        assertNotNull(adminInstance);

        // Verify the admin instance is properly configured
        // Note: This test only verifies lifecycle and caching behavior, not internal Keycloak configuration
        assertDoesNotThrow(() -> adminInstance.toString());
    }

    @Test
    void testLoginInstanceCreationLifecycleAndCachingOnly() {
        Keycloak loginInstance = keycloakProvider.getLoginInstance();
        assertNotNull(loginInstance);

        // Note: This test only verifies lifecycle and caching behavior, not internal Keycloak configuration
        assertDoesNotThrow(() -> loginInstance.toString());
    }

    @Test
    void testDifferentInstancesLifecycleAndCachingOnly() {
        Keycloak adminInstance = keycloakProvider.getAdminInstance();
        Keycloak loginInstance = keycloakProvider.getLoginInstance();

        // Admin and login instances should be different objects
        assertNotSame(adminInstance, loginInstance);

        // But multiple calls to the same method should return the same instance (caching)
        Keycloak adminInstance2 = keycloakProvider.getAdminInstance();
        Keycloak loginInstance2 = keycloakProvider.getLoginInstance();

        assertSame(adminInstance, adminInstance2);
        assertSame(loginInstance, loginInstance2);
    }

    @Test
    void testConstructorWithDifferentParameters() {
        KeycloakProvider provider = new KeycloakProvider(
            "https://keycloak.example.com",
            "production-realm",
            "prod-client",
            "prod-secret",
            "prod-admin-client",
            "prod-admin-secret"
        );
        
        assertEquals("https://keycloak.example.com", provider.getServerURL());
        assertEquals("production-realm", provider.getRealm());
        assertNotNull(provider.getAdminInstance());
        assertNotNull(provider.getLoginInstance());
    }

    @Test
    void testInstanceConsistencyLifecycleAndCachingOnly() {
        // Verify that instances are consistent across multiple calls
        Keycloak admin1 = keycloakProvider.getAdminInstance();
        Keycloak admin2 = keycloakProvider.getAdminInstance();
        Keycloak admin3 = keycloakProvider.getAdminInstance();
        
        Keycloak login1 = keycloakProvider.getLoginInstance();
        Keycloak login2 = keycloakProvider.getLoginInstance();
        Keycloak login3 = keycloakProvider.getLoginInstance();
        
        // All admin instances should be the same
        assertSame(admin1, admin2);
        assertSame(admin2, admin3);
        
        // All login instances should be the same
        assertSame(login1, login2);
        assertSame(login2, login3);
        
        // Admin and login instances should be different
        assertNotSame(admin1, login1);
    }
}
