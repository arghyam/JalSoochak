package org.arghyam.jalsoochak.tenant.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.arghyam.jalsoochak.tenant.config.JwtAuthConverter;
import org.arghyam.jalsoochak.tenant.config.SecurityConfig;
import org.arghyam.jalsoochak.tenant.config.SecurityExceptionHandler;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingSecretRewrapResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingSecretRotationResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.exception.SecretStoreUnavailableException;
import org.arghyam.jalsoochak.tenant.service.TenantMessagingSecretService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MESSAGING-PROVIDER-SECRETS: security boundary tests for
 * {@link SystemMessagingSecretController}.
 *
 * <p>Both endpoints are SUPER_USER only, the per-tenant rotation included. A STATE_ADMIN
 * reaching the rotation for their own tenant would be the wrong boundary: rotating a key is
 * an operational response to a suspected exposure, and the state admin is the party whose
 * credentials may have leaked.
 */
@WebMvcTest(SystemMessagingSecretController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityExceptionHandler.class,
        GlobalExceptionHandler.class})
@DisplayName("System Messaging Secret Controller Security Tests")
class SystemMessagingSecretControllerSecurityTest {

    private static final String REWRAP = "/api/v1/system/messaging-secrets/rewrap";
    private static final String ROTATE = "/api/v1/system/messaging-secrets/tenants/101/rotate";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantMessagingSecretService messagingSecretService;

    @Nested
    @DisplayName("POST /rewrap")
    class Rewrap {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void noTokenIsUnauthorized() throws Exception {
            mockMvc.perform(post(REWRAP)).andExpect(status().isUnauthorized());

            verify(messagingSecretService, never()).rewrapDataKeys();
        }

        @Test
        @DisplayName("STATE_ADMIN is forbidden")
        void stateAdminIsForbidden() throws Exception {
            mockMvc.perform(post(REWRAP)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verify(messagingSecretService, never()).rewrapDataKeys();
        }

        @Test
        @DisplayName("SUPER_USER gets the rotation progress counts")
        void superUserReceivesCounts() throws Exception {
            when(messagingSecretService.rewrapDataKeys()).thenReturn(MessagingSecretRewrapResponseDTO.builder()
                    .activeMasterKeyId("v2").totalKeys(3).rewrapped(2).alreadyActive(1)
                    .failedTenantIds(List.of()).build());

            mockMvc.perform(post(REWRAP)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.activeMasterKeyId").value("v2"))
                    .andExpect(jsonPath("$.data.rewrapped").value(2))
                    .andExpect(jsonPath("$.data.alreadyActive").value(1))
                    .andExpect(jsonPath("$.data.failedTenantIds").isEmpty());
        }

        @Test
        @DisplayName("A deployment with no master key answers 503")
        void noMasterKeyIsServiceUnavailable() throws Exception {
            when(messagingSecretService.rewrapDataKeys())
                    .thenThrow(new SecretStoreUnavailableException("no master key is configured"));

            mockMvc.perform(post(REWRAP)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    @Nested
    @DisplayName("POST /tenants/{tenantId}/rotate")
    class Rotate {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void noTokenIsUnauthorized() throws Exception {
            mockMvc.perform(post(ROTATE)).andExpect(status().isUnauthorized());

            verify(messagingSecretService, never()).rotateTenantDataKey(anyInt());
        }

        @Test
        @DisplayName("STATE_ADMIN cannot rotate even their own tenant's key")
        void stateAdminIsForbidden() throws Exception {
            mockMvc.perform(post(ROTATE)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verify(messagingSecretService, never()).rotateTenantDataKey(anyInt());
        }

        @Test
        @DisplayName("SUPER_USER gets the old and new key versions")
        void superUserReceivesVersions() throws Exception {
            when(messagingSecretService.rotateTenantDataKey(101))
                    .thenReturn(MessagingSecretRotationResponseDTO.builder()
                            .tenantId(101).previousKeyVersion(1).newKeyVersion(2).secretsReEncrypted(2)
                            .channels(List.of(MessagingChannel.SMS)).build());

            mockMvc.perform(post(ROTATE)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.previousKeyVersion").value(1))
                    .andExpect(jsonPath("$.data.newKeyVersion").value(2))
                    .andExpect(jsonPath("$.data.secretsReEncrypted").value(2))
                    .andExpect(jsonPath("$.data.channels[0]").value("SMS"));
        }

        @Test
        @DisplayName("A tenant with no stored secrets answers 404")
        void nothingToRotateIsNotFound() throws Exception {
            when(messagingSecretService.rotateTenantDataKey(101))
                    .thenThrow(new ResourceNotFoundException(
                            "Tenant with tenantId 101 has no messaging secret key to rotate"));

            mockMvc.perform(post(ROTATE)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isNotFound());
        }
    }
}
