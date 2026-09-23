package org.arghyam.jalsoochak.tenant.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.arghyam.jalsoochak.tenant.dto.internal.EmailProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SmsProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.WhatsAppMessagesConfigDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TenantConfigKeyEnum")
class TenantConfigKeyEnumTest {

    @Nested
    @DisplayName("isPublic contract")
    class IsPublicTests {

        @Test
        void publicKeys_containExpectedEntries() {
            List<TenantConfigKeyEnum> publicKeys = Arrays.stream(TenantConfigKeyEnum.values())
                    .filter(TenantConfigKeyEnum::isPublic)
                    .toList();

            assertThat(publicKeys).containsExactlyInAnyOrder(
                    TenantConfigKeyEnum.AVERAGE_MEMBERS_PER_HOUSEHOLD,
                    TenantConfigKeyEnum.WATER_NORM,
                    TenantConfigKeyEnum.DATE_FORMAT_SCREEN,
                    TenantConfigKeyEnum.DATE_FORMAT_TABLE,
                    TenantConfigKeyEnum.SUPPORTED_LANGUAGES,
                    TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_1,
                    TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_2,
                    TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_3,
                    TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_4,
                    TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_5,
                    TenantConfigKeyEnum.DISPLAY_MAP_LGD_LEVEL_6,
                    TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_1,
                    TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_2,
                    TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_3,
                    TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_4,
                    TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_5,
                    TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAP_LEVEL_6
            );
        }

        @Test
        void sensitiveKeys_areNotPublic() {
            assertThat(TenantConfigKeyEnum.MESSAGE_BROKER_CONNECTION_SETTINGS.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.STATE_IT_SYSTEM_CONNECTION.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.WHATSAPP_MESSAGE_TEMPLATES.isPublic()).isFalse();
        }

        @Test
        void operationalKeys_areNotPublic() {
            assertThat(TenantConfigKeyEnum.PUMP_OPERATOR_REMINDER_NUDGE_TIME.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.FIELD_STAFF_ESCALATION_RULES.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.DATA_CONSOLIDATION_TIME.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.STATE_DATA_RECONCILIATION_TIME.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.METER_CHANGE_REASONS.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.SUPPLY_OUTAGE_REASONS.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.LOCATION_CHECK_REQUIRED.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.INCLUDED_WORK_STATUSES.isPublic()).isFalse();
        }
    }

    @Nested
    @DisplayName("mandatory contract")
    class MandatoryTests {

        @Test
        @DisplayName("Mandatory keys are correctly marked")
        void mandatoryKeys_areCorrectlyMarked() {
            List<TenantConfigKeyEnum> mandatoryKeys = Arrays.stream(TenantConfigKeyEnum.values())
                    .filter(TenantConfigKeyEnum::isMandatory)
                    .toList();

            assertThat(mandatoryKeys).containsExactlyInAnyOrder(
                    TenantConfigKeyEnum.DATE_FORMAT_SCREEN,
                    TenantConfigKeyEnum.DATE_FORMAT_TABLE,
                    TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS,
                    TenantConfigKeyEnum.TENANT_LOGO,
                    TenantConfigKeyEnum.METER_CHANGE_REASONS,
                    TenantConfigKeyEnum.SUPPORTED_LANGUAGES,
                    TenantConfigKeyEnum.LOCATION_CHECK_REQUIRED,
                    TenantConfigKeyEnum.WATER_NORM,
                    TenantConfigKeyEnum.TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD,
                    TenantConfigKeyEnum.PUMP_OPERATOR_REMINDER_NUDGE_TIME,
                    TenantConfigKeyEnum.FIELD_STAFF_ESCALATION_RULES,
                    TenantConfigKeyEnum.DATA_CONSOLIDATION_TIME,
                    TenantConfigKeyEnum.AVERAGE_MEMBERS_PER_HOUSEHOLD,
                    TenantConfigKeyEnum.SUPPLY_OUTAGE_REASONS
            );
        }

        @Test
        @DisplayName("Messaging provider settings keys are generic, private, managed and optional")
        void messagingProviderSettingsKeys_haveExpectedFlags() {
            for (TenantConfigKeyEnum key : List.of(
                    TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS,
                    TenantConfigKeyEnum.SMS_PROVIDER_SETTINGS)) {
                assertThat(key.getType()).isEqualTo(TenantConfigKeyEnum.ConfigType.GENERIC);
                // Never public: the settings name a tenant's provider account and, for SMTP, the
                // username on it.
                assertThat(key.isPublic()).isFalse();
                // Managed: the generic PUT /config must refuse them, so a settings value cannot be
                // written without the provider, allowlist and TLS checks.
                assertThat(key.isManagedValue()).isTrue();
                // Not mandatory: a tenant with no settings uses the system default provider, so
                // these must not block the ONBOARDED -> CONFIGURED transition.
                assertThat(key.isMandatory()).isFalse();
            }

            assertThat(TenantConfigKeyEnum.EMAIL_PROVIDER_SETTINGS.getDtoClass())
                    .isEqualTo(EmailProviderConfigDTO.class);
            assertThat(TenantConfigKeyEnum.SMS_PROVIDER_SETTINGS.getDtoClass())
                    .isEqualTo(SmsProviderConfigDTO.class);
        }

        @Test
        @DisplayName("Optional keys are correctly marked as non-mandatory")
        void optionalKeys_areNotMandatory() {
            assertThat(TenantConfigKeyEnum.WHATSAPP_MESSAGE_TEMPLATES.isMandatory()).isFalse();
            assertThat(TenantConfigKeyEnum.STATE_IT_SYSTEM_CONNECTION.isMandatory()).isFalse();
            assertThat(TenantConfigKeyEnum.STATE_DATA_RECONCILIATION_TIME.isMandatory()).isFalse();
            assertThat(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.isMandatory()).isFalse();
            assertThat(TenantConfigKeyEnum.MESSAGE_BROKER_CONNECTION_SETTINGS.isMandatory()).isFalse();
            assertThat(TenantConfigKeyEnum.MESSAGE_BROKER_CONNECTION_SETTINGS.isManagedValue()).isTrue();
        }

        @Test
        @DisplayName("getMandatoryKeys result matches keys with isMandatory=true")
        void getMandatoryKeys_matchesIsMandatoryFilter() {
            EnumSet<TenantConfigKeyEnum> mandatory = TenantConfigKeyEnum.getMandatoryKeys();
            List<TenantConfigKeyEnum> filtered = Arrays.stream(TenantConfigKeyEnum.values())
                    .filter(TenantConfigKeyEnum::isMandatory)
                    .toList();
            assertThat(mandatory).containsExactlyInAnyOrderElementsOf(filtered);
        }
    }

    @Nested
    @DisplayName("legacy alias contract")
    @SuppressWarnings("removal")
    class LegacyAliasTests {

        @Test
        @DisplayName("GLIFIC_MESSAGE_TEMPLATES is the only legacy alias, and it resolves to WHATSAPP_MESSAGE_TEMPLATES")
        void glificMessageTemplates_isAliasOfWhatsAppMessageTemplates() {
            List<TenantConfigKeyEnum> aliases = Arrays.stream(TenantConfigKeyEnum.values())
                    .filter(TenantConfigKeyEnum::isLegacyAlias)
                    .toList();

            assertThat(aliases).containsExactly(TenantConfigKeyEnum.GLIFIC_MESSAGE_TEMPLATES);
            assertThat(TenantConfigKeyEnum.GLIFIC_MESSAGE_TEMPLATES.canonical())
                    .isEqualTo(TenantConfigKeyEnum.WHATSAPP_MESSAGE_TEMPLATES);
        }

        @Test
        @DisplayName("Every canonical key resolves to itself")
        void canonicalKeys_resolveToThemselves() {
            for (TenantConfigKeyEnum key : TenantConfigKeyEnum.canonicalValues()) {
                assertThat(key.isLegacyAlias()).isFalse();
                assertThat(key.canonical()).isSameAs(key);
            }
        }

        @Test
        @DisplayName("canonicalValues() is every key except the alias")
        void canonicalValues_excludeOnlyTheAlias() {
            assertThat(TenantConfigKeyEnum.canonicalValues())
                    .isEqualTo(EnumSet.complementOf(EnumSet.of(TenantConfigKeyEnum.GLIFIC_MESSAGE_TEMPLATES)));
        }

        @Test
        @DisplayName("WHATSAPP_MESSAGE_TEMPLATES stays generic, private, optional and writable through PUT /config")
        void whatsAppMessageTemplates_keepsTheFlagsOfTheKeyItReplaces() {
            TenantConfigKeyEnum key = TenantConfigKeyEnum.WHATSAPP_MESSAGE_TEMPLATES;

            assertThat(key.getType()).isEqualTo(TenantConfigKeyEnum.ConfigType.GENERIC);
            assertThat(key.getDtoClass()).isEqualTo(WhatsAppMessagesConfigDTO.class);
            assertThat(key.isPublic()).isFalse();
            assertThat(key.isMandatory()).isFalse();
            // The admin UI saves the templates through the generic endpoint, which refuses managed keys.
            assertThat(key.isManagedValue()).isFalse();
        }

        @Test
        @DisplayName("The alias binds to the same DTO and writability, and is never public or mandatory")
        void alias_sharesStorageButIsNeverPublicOrMandatory() {
            TenantConfigKeyEnum alias = TenantConfigKeyEnum.GLIFIC_MESSAGE_TEMPLATES;

            assertThat(alias.getType()).isEqualTo(TenantConfigKeyEnum.ConfigType.GENERIC);
            assertThat(alias.getDtoClass()).isEqualTo(WhatsAppMessagesConfigDTO.class);
            assertThat(alias.isManagedValue()).isFalse();
            assertThat(alias.isPublic()).isFalse();
            assertThat(alias.isMandatory()).isFalse();
            assertThat(TenantConfigKeyEnum.getMandatoryKeys()).doesNotContain(alias);
        }
    }
}
