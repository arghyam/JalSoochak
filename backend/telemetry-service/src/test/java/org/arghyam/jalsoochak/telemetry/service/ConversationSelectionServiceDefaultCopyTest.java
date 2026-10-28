package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedChannelRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedItemRequest;
import org.arghyam.jalsoochak.telemetry.provider.whatsapp.WhatsAppContactDirectory;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserLanguagePreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The built-in copy of the channel and item steps: the prompt and confirmation an operator gets when
 * the tenant configures none, and the reply when the channel menu cannot be built — each in the
 * operator's language.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConversationSelectionService — built-in channel and item copy")
class ConversationSelectionServiceDefaultCopyTest {

    private static final String CONTACT = "919999900001";
    private static final String SCHEMA = "tenant_as";
    private static final int TENANT = 17;

    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private ConversationLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private ConversationTemplateService templatesService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;
    @Mock
    private UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    @Mock
    private WhatsAppContactDirectory whatsAppContactDirectory;
    @Spy
    private ConversationMessageResolver messageResolver = ConversationMessageResolverFixtures.real();

    private ConversationSelectionService service;

    @BeforeEach
    void setUp() {
        service = new ConversationSelectionService(
                operatorContextService, localizationService, tenantConfigRepository, templatesService,
                telemetryTenantRepository, userChannelPreferenceRepository, userLanguagePreferenceRepository,
                whatsAppContactDirectory, new ObjectMapper(), messageResolver);

        when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(new TelemetryOperatorWithSchema(
                SCHEMA, new TelemetryOperator(11L, TENANT, "Asha", "a@b.c", CONTACT, 1)));
        when(operatorContextService.resolveOperatorLanguage(any(), anyInt())).thenReturn("English");
        when(localizationService.normalizeLanguageKey(anyString())).thenReturn("english");
        when(localizationService.resolveLanguageKeyForContact(any())).thenReturn("english");
        when(templatesService.resolveScreenOptions(anyInt(), anyString())).thenReturn(List.of());
        when(templatesService.resolveScreenPrompt(anyInt(), anyString(), anyString())).thenReturn(Optional.empty());
        when(templatesService.resolveScreenConfirmationTemplate(anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(TENANT, "TENANT_SUPPORTED_CHANNELS"))
                .thenReturn(Optional.of("{\"channels\":[\"Bfm\",\"Iot\"]}"));
        when(tenantConfigRepository.findItemOptions(anyInt(), anyString()))
                .thenReturn(List.of("Submit Reading", "Report Issue"));
        when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, 11L)).thenReturn(Optional.of(99L));
    }

    private void operatorReadsHindi() {
        when(localizationService.normalizeLanguageKey(anyString())).thenReturn("hindi");
        when(localizationService.resolveLanguageKeyForContact(any())).thenReturn("hindi");
    }

    private static IntroRequest introRequest(String contactId) {
        IntroRequest request = new IntroRequest();
        request.setContactId(contactId);
        return request;
    }

    @Nested
    @DisplayName("channelSelectionMessage")
    class ChannelPrompt {

        @Test
        void usesTheBuiltInPromptWhenTheTenantConfiguresNone() {
            assertThat(service.channelSelectionMessage(introRequest(CONTACT)).getMessage()).isEqualTo(
                    "Please select your preferred channel by typing the corresponding number:\n1. Bfm\n2. Iot");
        }

        @Test
        void asksForTheBuiltInPromptInTheOperatorsLanguage() {
            operatorReadsHindi();

            service.channelSelectionMessage(introRequest(CONTACT));

            verify(messageResolver).get(ConversationMessageCode.CHANNEL_PROMPT, "hindi");
        }

        @Test
        void failsGracefullyWhenNoChannelsAreConfigured() {
            when(tenantConfigRepository.findConfigValue(TENANT, "TENANT_SUPPORTED_CHANNELS"))
                    .thenReturn(Optional.empty());

            var response = service.channelSelectionMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).isEqualTo("Channel selection could not be prepared.");
        }

        @Test
        void failsGracefullyInTheOperatorsLanguage() {
            operatorReadsHindi();
            when(tenantConfigRepository.findConfigValue(TENANT, "TENANT_SUPPORTED_CHANNELS"))
                    .thenReturn(Optional.empty());

            service.channelSelectionMessage(introRequest(CONTACT));

            verify(messageResolver).get(ConversationMessageCode.CHANNEL_SELECTION_NOT_PREPARED, "hindi");
        }
    }

    @Nested
    @DisplayName("selectedChannelMessage")
    class ChannelSelection {

        private SelectedChannelRequest request() {
            return SelectedChannelRequest.builder().contactId(CONTACT).channel("2").build();
        }

        @Test
        void confirmsWithTheBuiltInCopyWhenTheTenantConfiguresNone() {
            assertThat(service.selectedChannelMessage(request()).getMessage()).isEqualTo("Channel selected: Iot");
        }

        @Test
        void confirmsInTheOperatorsLanguage() {
            operatorReadsHindi();

            service.selectedChannelMessage(request());

            verify(messageResolver).get(ConversationMessageCode.CHANNEL_SELECTED, "hindi", "Iot");
        }
    }

    @Nested
    @DisplayName("itemSelectionMessage")
    class ItemPrompt {

        @Test
        void usesTheBuiltInPromptWhenTheTenantConfiguresNone() {
            assertThat(service.itemSelectionMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("Please select what you want to do:\n1. Submit Reading\n2. Report Issue");
        }

        @Test
        void prefersTheTenantConfiguredPrompt() {
            when(tenantConfigRepository.findItemSelectionPrompt(TENANT, "english"))
                    .thenReturn(Optional.of("What next?"));

            assertThat(service.itemSelectionMessage(introRequest(CONTACT)).getMessage())
                    .startsWith("What next?\n1. Submit Reading");
        }

        @Test
        void asksForTheBuiltInPromptInTheOperatorsLanguage() {
            operatorReadsHindi();

            service.itemSelectionMessage(introRequest(CONTACT));

            verify(messageResolver).get(ConversationMessageCode.ITEM_PROMPT, "hindi");
        }
    }

    @Nested
    @DisplayName("selectedItemMessage")
    class ItemSelection {

        private SelectedItemRequest request() {
            return SelectedItemRequest.builder().contactId(CONTACT).channel("2").build();
        }

        @Test
        void confirmsWithTheBuiltInCopyWhenTheTenantConfiguresNone() {
            assertThat(service.selectedItemMessage(request()).getMessage()).isEqualTo("Report Issue selected");
        }

        @Test
        void confirmsInTheOperatorsLanguage() {
            operatorReadsHindi();

            service.selectedItemMessage(request());

            verify(messageResolver).get(ConversationMessageCode.ITEM_SELECTED, "hindi", "Report Issue");
        }
    }
}
