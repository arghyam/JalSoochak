package org.arghyam.jalsoochak.tenant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.arghyam.jalsoochak.tenant.config.JwtAuthConverter;
import org.arghyam.jalsoochak.tenant.config.SecurityConfig;
import org.arghyam.jalsoochak.tenant.config.SecurityExceptionHandler;
import org.arghyam.jalsoochak.tenant.config.TenantSecurityEvaluator;
import org.arghyam.jalsoochak.tenant.dto.request.SetMessagingProviderSecretsRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderSecretStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.enums.SecretStatus;
import org.arghyam.jalsoochak.tenant.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.tenant.exception.SecretStoreUnavailableException;
import org.arghyam.jalsoochak.tenant.service.TenantMessagingSecretService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MESSAGING-PROVIDER-SECRETS: security boundary tests for
 * {@link TenantMessagingProviderController}.
 *
 * <p>The real filter chain and the real {@link TenantSecurityEvaluator} SpEL bean are wired
 * in, with only the repository behind the evaluator mocked — otherwise a broken
 * {@code @RequiresTenantAccess} expression would pass silently.
 *
 * <p>What matters here is that a STATE_ADMIN cannot reach another state's credentials: a
 * shared message-service can read every tenant's secret, so the tenant boundary on the write
 * path is the only thing keeping one state out of another's provider account.
 */
@WebMvcTest(TenantMessagingProviderController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityExceptionHandler.class,
        GlobalExceptionHandler.class})
@DisplayName("Tenant Messaging Provider Controller Security Tests")
class TenantMessagingProviderControllerSecurityTest {

    private static final String MP_SECRETS = "/api/v1/tenants/101/messaging-providers/SMS/secrets";
    private static final String TR_SECRETS = "/api/v1/tenants/102/messaging-providers/SMS/secrets";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TenantMessagingSecretService messagingSecretService;

    /**
     * Registered under the name the {@code @RequiresTenantAccess} SpEL expression uses. Without
     * the explicit name the mock lands under its type's default name, the expression fails to
     * resolve {@code @tenantSecurity}, and every STATE_ADMIN request 400s instead of being
     * authorised — which would look like a broken test rather than a broken annotation.
     */
    @MockBean(name = "tenantSecurity")
    private TenantSecurityEvaluator tenantSecurity;

    private String body() throws Exception {
        SetMessagingProviderSecretsRequestDTO request = new SetMessagingProviderSecretsRequestDTO();
        request.setSecrets(Map.of("authKey", "smscountry-auth-key"));
        return objectMapper.writeValueAsString(request);
    }

    private static MessagingProviderSecretStatusResponseDTO statusBody(SecretStatus authKey) {
        return MessagingProviderSecretStatusResponseDTO.builder()
                .tenantId(101)
                .channel(MessagingChannel.SMS)
                .secrets(Map.of("authKey", authKey, "authToken", SecretStatus.MISSING))
                .build();
    }

    // ── PUT ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /{channel}/secrets")
    class PutSecrets {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void noTokenIsUnauthorized() throws Exception {
            mockMvc.perform(put(MP_SECRETS).contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isUnauthorized());

            verify(messagingSecretService, never()).setSecrets(anyInt(), any(), any());
        }

        @Test
        @DisplayName("SUPER_USER may write any tenant's secrets")
        void superUserMayWriteAnyTenant() throws Exception {
            when(messagingSecretService.setSecrets(eq(101), eq(MessagingChannel.SMS), any()))
                    .thenReturn(statusBody(SecretStatus.SET));

            mockMvc.perform(put(MP_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets.authKey").value("SET"));

            // SUPER_USER short-circuits the SpEL expression, so no tenant lookup happens.
            verify(tenantSecurity, never()).isOwnTenant(anyInt());
        }

        @Test
        @DisplayName("STATE_ADMIN may write their own tenant's secrets")
        void stateAdminMayWriteOwnTenant() throws Exception {
            when(tenantSecurity.isOwnTenant(101)).thenReturn(true);
            when(messagingSecretService.setSecrets(eq(101), eq(MessagingChannel.SMS), any()))
                    .thenReturn(statusBody(SecretStatus.SET));

            mockMvc.perform(put(MP_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isOk());

            verify(messagingSecretService).setSecrets(eq(101), eq(MessagingChannel.SMS), any());
        }

        @Test
        @DisplayName("STATE_ADMIN is forbidden from another tenant's secrets")
        void stateAdminMayNotWriteAnotherTenant() throws Exception {
            when(tenantSecurity.isOwnTenant(102)).thenReturn(false);

            mockMvc.perform(put(TR_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isForbidden());

            verify(messagingSecretService, never()).setSecrets(anyInt(), any(), any());
        }

        @Test
        @DisplayName("Staff roles are forbidden outright")
        void staffIsForbidden() throws Exception {
            mockMvc.perform(put(MP_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PUMP_OPERATOR")))
                    .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isForbidden());

            verify(messagingSecretService, never()).setSecrets(anyInt(), any(), any());
            verify(tenantSecurity, never()).isOwnTenant(anyInt());
        }

        @Test
        @DisplayName("An empty secrets map is rejected as a validation error")
        void emptySecretsRejected() throws Exception {
            mockMvc.perform(put(MP_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"secrets\":{}}"))
                    .andExpect(status().isBadRequest());

            verify(messagingSecretService, never()).setSecrets(anyInt(), any(), any());
        }

        @Test
        @DisplayName("A blank value is rejected without the response echoing it")
        void blankValueRejectedWithoutEcho() throws Exception {
            mockMvc.perform(put(MP_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"secrets\":{\"authKey\":\"\"}}"))
                    .andExpect(status().isBadRequest())
                    // The name is in the path, the value is not in the message — which is the point:
                    // a validation failure on a credential must not echo the credential.
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("secrets[authKey]"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("secret value must not be blank"));
        }

        @Test
        @DisplayName("An unknown channel is a 400 listing the valid ones")
        void unknownChannelRejected() throws Exception {
            mockMvc.perform(put("/api/v1/tenants/101/messaging-providers/WHATSAPP/secrets")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("EMAIL, SMS")));
        }

        @Test
        @DisplayName("A deployment with no master key answers 503")
        void noMasterKeyIsServiceUnavailable() throws Exception {
            when(messagingSecretService.setSecrets(eq(101), eq(MessagingChannel.SMS), any()))
                    .thenThrow(new SecretStoreUnavailableException(
                            "Tenant messaging secret storage is not available: no master key is configured "
                                    + "for this deployment."));

            mockMvc.perform(put(MP_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("no master key is configured")));
        }
    }

    // ── GET ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /{channel}/secrets")
    class GetSecretStatus {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void noTokenIsUnauthorized() throws Exception {
            mockMvc.perform(get(MP_SECRETS)).andExpect(status().isUnauthorized());

            verify(messagingSecretService, never()).getSecretStatus(anyInt(), any());
        }

        @Test
        @DisplayName("STATE_ADMIN is forbidden from another tenant's secret status")
        void stateAdminMayNotReadAnotherTenant() throws Exception {
            when(tenantSecurity.isOwnTenant(102)).thenReturn(false);

            mockMvc.perform(get(TR_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verify(messagingSecretService, never()).getSecretStatus(anyInt(), any());
        }

        @Test
        @DisplayName("The response carries statuses only — no field can hold a value")
        void returnsStatusesOnly() throws Exception {
            when(messagingSecretService.getSecretStatus(101, MessagingChannel.SMS))
                    .thenReturn(statusBody(SecretStatus.SET));

            mockMvc.perform(get(MP_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets.authKey").value("SET"))
                    .andExpect(jsonPath("$.data.secrets.authToken").value("MISSING"))
                    .andExpect(jsonPath("$.data.values").doesNotExist())
                    .andExpect(jsonPath("$.data.ciphertext").doesNotExist());
        }
    }

    // ── DELETE ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /{channel}/secrets")
    class DeleteSecrets {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void noTokenIsUnauthorized() throws Exception {
            mockMvc.perform(delete(MP_SECRETS)).andExpect(status().isUnauthorized());

            verify(messagingSecretService, never()).deleteSecrets(anyInt(), any());
        }

        @Test
        @DisplayName("STATE_ADMIN is forbidden from deleting another tenant's secrets")
        void stateAdminMayNotDeleteAnotherTenant() throws Exception {
            when(tenantSecurity.isOwnTenant(102)).thenReturn(false);

            mockMvc.perform(delete(TR_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verify(messagingSecretService, never()).deleteSecrets(anyInt(), any());
        }

        @Test
        @DisplayName("SUPER_USER may delete, and the response reports everything MISSING")
        void superUserMayDelete() throws Exception {
            when(messagingSecretService.deleteSecrets(101, MessagingChannel.SMS))
                    .thenReturn(statusBody(SecretStatus.MISSING));

            mockMvc.perform(delete(MP_SECRETS)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets.authKey").value("MISSING"));
        }
    }
}
