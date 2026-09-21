package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.EmailProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SmsProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetMessagingProviderSettingsRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderSecretStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.EmailProviderType;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.enums.SecretStatus;
import org.arghyam.jalsoochak.tenant.enums.SmsProviderType;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.event.TenantConfigUpdatedEvent;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.service.MessagingProviderSettingsValidator;
import org.arghyam.jalsoochak.tenant.service.TenantMessagingSecretService;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.arghyam.jalsoochak.tenant.util.TenantConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MESSAGING-PROVIDER-SETTINGS: {@link TenantMessagingProviderServiceImpl}.
 *
 * <p>Two things this leans on hardest. First, that a settings write raises
 * {@code TenantConfigUpdatedEvent} under the channel's settings key — without it message-service
 * keeps sending through the old provider until the cache TTL expires. Second, that {@code usable}
 * is only true when the credentials the chosen provider needs are actually stored, because settings
 * alone silently leave the tenant on the system default.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantMessagingProviderServiceImpl Tests")
class TenantMessagingProviderServiceImplTest {

    private static final Integer TENANT_ID = 101;
    private static final String STATE_CODE = "MP";
    private static final String USER_UUID = "11111111-1111-1111-1111-111111111111";
    private static final Integer USER_ID = 500;

    @Mock
    private TenantCommonRepository tenantCommonRepository;

    @Mock
    private TenantMessagingSecretService messagingSecretService;

    @Mock
    private MessagingProviderSettingsValidator settingsValidator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ObjectMapper objectMapper;
    private MockedStatic<SecurityUtils> mockedSecurityUtils;
    private TenantMessagingProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new TenantMessagingProviderServiceImpl(
                tenantCommonRepository, messagingSecretService, settingsValidator, objectMapper, eventPublisher);
        mockedSecurityUtils = mockStatic(SecurityUtils.class);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUserUuid).thenReturn(USER_UUID);
    }

    @AfterEach
    void tearDown() {
        mockedSecurityUtils.close();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private void givenOnboardedTenant() {
        lenient().when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(
                TenantResponseDTO.builder().id(TENANT_ID).stateCode(STATE_CODE)
                        .status(TenantStatusEnum.CONFIGURED.name()).build()));
    }

    private void givenCurrentUser() {
        lenient().when(tenantCommonRepository.findUserIdByUuid(USER_UUID)).thenReturn(Optional.of(USER_ID));
    }

    private void givenNoStoredSettings() {
        lenient().when(tenantCommonRepository.findConfigByTenantAndKey(eq(TENANT_ID), anyString()))
                .thenReturn(Optional.empty());
    }

    private void givenStoredSettings(TenantConfigKeyEnum key, String json) {
        lenient().when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, key.name()))
                .thenReturn(Optional.of(ConfigDTO.builder().configKey(key.name()).configValue(json).build()));
    }

    private void givenUpsertEchoes() {
        lenient().when(tenantCommonRepository.upsertConfig(eq(TENANT_ID), anyString(), anyString(), eq(USER_ID)))
                .thenAnswer(inv -> Optional.of(ConfigDTO.builder()
                        .configKey(inv.getArgument(1)).configValue(inv.getArgument(2)).build()));
    }

    private void givenSecretStatus(MessagingChannel channel, Map<String, SecretStatus> secrets) {
        lenient().when(messagingSecretService.getSecretStatus(TENANT_ID, channel))
                .thenReturn(MessagingProviderSecretStatusResponseDTO.builder()
                        .tenantId(TENANT_ID).channel(channel).secrets(secrets).keyVersion(1).build());
    }

    private void givenAllSecretsMissing() {
        givenSecretStatus(MessagingChannel.EMAIL,
                Map.of("apiKey", SecretStatus.MISSING, "password", SecretStatus.MISSING));
        givenSecretStatus(MessagingChannel.SMS,
                Map.of("authKey", SecretStatus.MISSING, "authToken", SecretStatus.MISSING));
    }

    private static EmailProviderConfigDTO smtpSettings() {
        return EmailProviderConfigDTO.builder()
                .provider(EmailProviderType.SMTP)
                .fromAddress("no-reply@mp.gov.in")
                .smtp(EmailProviderConfigDTO.SmtpSettings.builder()
                        .host("smtp.mp.gov.in").port(587).username("jalsoochak").startTls(true).build())
                .build();
    }

    private static SmsProviderConfigDTO smsCountrySettings() {
        return SmsProviderConfigDTO.builder()
                .provider(SmsProviderType.SMSCOUNTRY)
                .smscountry(SmsProviderConfigDTO.SmsCountrySettings.builder()
                        .senderId("JLSCHK").dltPrincipalEntityId("PE-1").dltTemplateId("DT-1")
                        .dltHeaderId("DH-1").build())
                .build();
    }

    private static SetMessagingProviderSettingsRequestDTO request(
            EmailProviderConfigDTO email, SmsProviderConfigDTO sms) {
        SetMessagingProviderSettingsRequestDTO request = new SetMessagingProviderSettingsRequestDTO();
        request.setEmail(email);
        request.setSms(sms);
        return request;
    }

    // ── setProviderSettings ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("setProviderSettings")
    class SetSettings {

        @Test
        @DisplayName("Writes the email settings under EMAIL_PROVIDER_SETTINGS")
        void writesEmailSettings() throws Exception {
            givenOnboardedTenant();
            givenCurrentUser();
            givenNoStoredSettings();
            givenUpsertEchoes();
            givenAllSecretsMissing();

            service.setProviderSettings(TENANT_ID, request(smtpSettings(), null));

            ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
            verify(tenantCommonRepository).upsertConfig(eq(TENANT_ID),
                    eq(TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS.name()), value.capture(), eq(USER_ID));

            EmailProviderConfigDTO stored =
                    objectMapper.readValue(value.getValue(), EmailProviderConfigDTO.class);
            assertThat(stored.getProvider()).isEqualTo(EmailProviderType.SMTP);
            assertThat(stored.getSmtp().getHost()).isEqualTo("smtp.mp.gov.in");
            // The serialized form must hold no credential of any kind.
            assertThat(value.getValue()).doesNotContain("password");
        }

        @Test
        @DisplayName("A channel left out of the request is not touched")
        void absentChannelUntouched() {
            givenOnboardedTenant();
            givenCurrentUser();
            givenNoStoredSettings();
            givenUpsertEchoes();
            givenAllSecretsMissing();

            service.setProviderSettings(TENANT_ID, request(smtpSettings(), null));

            verify(tenantCommonRepository, never()).upsertConfig(anyInt(),
                    eq(TenantConfigKeyEnum.SMS_PROVIDER_SETTINGS.name()), anyString(), anyInt());
        }

        @Test
        @DisplayName("Both channels in one request write both keys and raise one event naming both")
        void bothChannelsInOneEvent() {
            givenOnboardedTenant();
            givenCurrentUser();
            givenNoStoredSettings();
            givenUpsertEchoes();
            givenAllSecretsMissing();

            service.setProviderSettings(TENANT_ID, request(smtpSettings(), smsCountrySettings()));

            verify(tenantCommonRepository).upsertConfig(eq(TENANT_ID),
                    eq(TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS.name()), anyString(), eq(USER_ID));
            verify(tenantCommonRepository).upsertConfig(eq(TENANT_ID),
                    eq(TenantConfigKeyEnum.SMS_PROVIDER_SETTINGS.name()), anyString(), eq(USER_ID));

            ArgumentCaptor<TenantConfigUpdatedEvent> event =
                    ArgumentCaptor.forClass(TenantConfigUpdatedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());
            assertThat(event.getValue().getConfigKeys()).containsExactlyInAnyOrder(
                    TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS.name(),
                    TenantConfigKeyEnum.SMS_PROVIDER_SETTINGS.name());
            assertThat(event.getValue().getTenantId()).isEqualTo(TENANT_ID);
            assertThat(event.getValue().getStateCode()).isEqualTo(STATE_CODE);
        }

        @Test
        @DisplayName("The event names only the channel that changed")
        void eventNamesOnlyChangedChannel() {
            givenOnboardedTenant();
            givenCurrentUser();
            givenNoStoredSettings();
            givenUpsertEchoes();
            givenAllSecretsMissing();

            service.setProviderSettings(TENANT_ID, request(null, smsCountrySettings()));

            ArgumentCaptor<TenantConfigUpdatedEvent> event =
                    ArgumentCaptor.forClass(TenantConfigUpdatedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());
            assertThat(event.getValue().getConfigKeys())
                    .containsExactly(TenantConfigKeyEnum.SMS_PROVIDER_SETTINGS.name());
        }

        @Test
        @DisplayName("Every present channel is validated before anything is written")
        void validatesBeforeWriting() {
            givenOnboardedTenant();
            givenCurrentUser();
            givenNoStoredSettings();
            givenAllSecretsMissing();
            org.mockito.Mockito.doThrow(new InvalidConfigValueException("bad sms"))
                    .when(settingsValidator).validateSmsSettings(any());

            // The email block is valid, but the SMS one is not: nothing may be written, or the
            // response would describe a half-applied change.
            assertThatThrownBy(() -> service.setProviderSettings(
                    TENANT_ID, request(smtpSettings(), smsCountrySettings())))
                    .isInstanceOf(InvalidConfigValueException.class);

            verify(tenantCommonRepository, never()).upsertConfig(anyInt(), anyString(), anyString(), anyInt());
            verify(eventPublisher, never()).publishEvent(any(TenantConfigUpdatedEvent.class));
        }

        @Test
        @DisplayName("An empty request is rejected rather than treated as a no-op")
        void emptyRequestRejected() {
            assertThatThrownBy(() -> service.setProviderSettings(TENANT_ID, request(null, null)))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("email");

            verifyNoInteractions(tenantCommonRepository, settingsValidator, eventPublisher);
        }

        @Test
        @DisplayName("The system tenant holds platform defaults and has no provider account")
        void systemTenantRejected() {
            assertThatThrownBy(() -> service.setProviderSettings(
                    TenantConstants.SYSTEM_TENANT_ID, request(smtpSettings(), null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("system tenant");
        }

        @Test
        @DisplayName("A tenant that was never onboarded is treated as not found")
        void registeredTenantRejected() {
            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(
                    TenantResponseDTO.builder().id(TENANT_ID).stateCode(STATE_CODE)
                            .status(TenantStatusEnum.REGISTERED.name()).build()));

            assertThatThrownBy(() -> service.setProviderSettings(TENANT_ID, request(smtpSettings(), null)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not onboarded");
        }

        @Test
        @DisplayName("An unknown tenant is not found")
        void unknownTenantRejected() {
            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setProviderSettings(TENANT_ID, request(smtpSettings(), null)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── getProviderConfig ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getProviderConfig")
    class GetConfig {

        @Test
        @DisplayName("A channel with no settings reports null settings and is not usable")
        void unconfiguredChannel() {
            givenOnboardedTenant();
            givenNoStoredSettings();
            givenAllSecretsMissing();

            MessagingProviderConfigResponseDTO result = service.getProviderConfig(TENANT_ID);

            assertThat(result.getEmail().getSettings()).isNull();
            assertThat(result.getEmail().isUsable()).isFalse();
            assertThat(result.getSms().getSettings()).isNull();
            assertThat(result.getSms().isUsable()).isFalse();
        }

        @Test
        @DisplayName("Settings with the provider's credential stored are usable")
        void configuredAndCredentialled() {
            givenOnboardedTenant();
            givenNoStoredSettings();
            givenStoredSettings(TenantConfigKeyEnum.SMS_PROVIDER_SETTINGS,
                    "{\"provider\":\"smscountry\",\"smscountry\":{\"senderId\":\"JLSCHK\","
                            + "\"dltPrincipalEntityId\":\"PE-1\",\"dltTemplateId\":\"DT-1\","
                            + "\"dltHeaderId\":\"DH-1\"}}");
            givenSecretStatus(MessagingChannel.SMS,
                    Map.of("authKey", SecretStatus.SET, "authToken", SecretStatus.SET));
            givenSecretStatus(MessagingChannel.EMAIL,
                    Map.of("apiKey", SecretStatus.MISSING, "password", SecretStatus.MISSING));

            MessagingProviderConfigResponseDTO result = service.getProviderConfig(TENANT_ID);

            assertThat(result.getSms().getSettings()).isInstanceOf(SmsProviderConfigDTO.class);
            assertThat(result.getSms().isUsable()).isTrue();
            assertThat(result.getSms().getKeyVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("Settings with a credential still MISSING are not usable")
        void configuredButCredentialMissing() {
            givenOnboardedTenant();
            givenNoStoredSettings();
            givenStoredSettings(TenantConfigKeyEnum.SMS_PROVIDER_SETTINGS,
                    "{\"provider\":\"smscountry\",\"smscountry\":{\"senderId\":\"JLSCHK\","
                            + "\"dltPrincipalEntityId\":\"PE-1\",\"dltTemplateId\":\"DT-1\","
                            + "\"dltHeaderId\":\"DH-1\"}}");
            givenSecretStatus(MessagingChannel.SMS,
                    Map.of("authKey", SecretStatus.SET, "authToken", SecretStatus.MISSING));
            givenSecretStatus(MessagingChannel.EMAIL,
                    Map.of("apiKey", SecretStatus.MISSING, "password", SecretStatus.MISSING));

            // Half-credentialled is the case a state admin is most likely to hit and least likely
            // to notice: message-service would fall back to the system default without complaint.
            assertThat(service.getProviderConfig(TENANT_ID).getSms().isUsable()).isFalse();
        }

        @Test
        @DisplayName("Only the provider's own credential counts towards usable")
        void onlyTheProvidersOwnCredentialCounts() {
            givenOnboardedTenant();
            givenNoStoredSettings();
            givenStoredSettings(TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS,
                    "{\"provider\":\"smtp\",\"fromAddress\":\"a@mp.gov.in\",\"smtp\":"
                            + "{\"host\":\"smtp.mp.gov.in\",\"port\":587,\"username\":\"u\",\"startTls\":true}}");
            // SMTP needs 'password'. A stored SendGrid 'apiKey' must not make it look ready.
            givenSecretStatus(MessagingChannel.EMAIL,
                    Map.of("apiKey", SecretStatus.SET, "password", SecretStatus.MISSING));
            givenSecretStatus(MessagingChannel.SMS,
                    Map.of("authKey", SecretStatus.MISSING, "authToken", SecretStatus.MISSING));

            assertThat(service.getProviderConfig(TENANT_ID).getEmail().isUsable()).isFalse();
        }

        @Test
        @DisplayName("A malformed stored value is reported, not returned as null settings")
        void malformedStoredValue() {
            givenOnboardedTenant();
            givenNoStoredSettings();
            givenStoredSettings(TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS, "{not json");

            assertThatThrownBy(() -> service.getProviderConfig(TENANT_ID))
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining(TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS.name());
        }

        @Test
        @DisplayName("Reading does not need the current user")
        void readDoesNotResolveUser() {
            givenOnboardedTenant();
            givenNoStoredSettings();
            givenAllSecretsMissing();

            service.getProviderConfig(TENANT_ID);

            verify(tenantCommonRepository, never()).findUserIdByUuid(anyString());
        }
    }

    // ── deleteProviderSettings ──────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteProviderSettings")
    class DeleteSettings {

        @Test
        @DisplayName("Soft-deletes the channel's key and raises the eviction event")
        void deletesAndEvicts() {
            givenOnboardedTenant();
            givenCurrentUser();
            givenNoStoredSettings();
            givenAllSecretsMissing();
            when(tenantCommonRepository.softDeleteConfig(
                    TENANT_ID, TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS.name(), USER_ID)).thenReturn(1);

            service.deleteProviderSettings(TENANT_ID, MessagingChannel.EMAIL);

            ArgumentCaptor<TenantConfigUpdatedEvent> event =
                    ArgumentCaptor.forClass(TenantConfigUpdatedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());
            assertThat(event.getValue().getConfigKeys())
                    .containsExactly(TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS.name());
        }

        @Test
        @DisplayName("Deleting a channel that had no settings raises no event")
        void noRowNoEvent() {
            givenOnboardedTenant();
            givenCurrentUser();
            givenNoStoredSettings();
            givenAllSecretsMissing();
            when(tenantCommonRepository.softDeleteConfig(
                    TENANT_ID, TenantConfigKeyEnum.SMS_PROVIDER_SETTINGS.name(), USER_ID)).thenReturn(0);

            service.deleteProviderSettings(TENANT_ID, MessagingChannel.SMS);

            verify(eventPublisher, never()).publishEvent(any(TenantConfigUpdatedEvent.class));
        }

        @Test
        @DisplayName("Deleting settings leaves the stored credentials alone")
        void leavesSecretsAlone() {
            givenOnboardedTenant();
            givenCurrentUser();
            givenNoStoredSettings();
            givenAllSecretsMissing();
            when(tenantCommonRepository.softDeleteConfig(
                    TENANT_ID, TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS.name(), USER_ID)).thenReturn(1);

            service.deleteProviderSettings(TENANT_ID, MessagingChannel.EMAIL);

            // Removing the settings moves the tenant back to the system default; dropping the
            // credentials as well would make switching back a re-entry rather than a re-enable.
            verify(messagingSecretService, never()).deleteSecrets(anyInt(), any());
        }

        @Test
        @DisplayName("The system tenant is refused here too")
        void systemTenantRejected() {
            assertThatThrownBy(() -> service.deleteProviderSettings(
                    TenantConstants.SYSTEM_TENANT_ID, MessagingChannel.EMAIL))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
