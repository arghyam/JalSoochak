package org.arghyam.jalsoochak.tenant.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

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
                    TenantConfigKeyEnum.DISPLAY_DEPARTMENT_MAPS
            );
        }

        @Test
        void sensitiveKeys_areNotPublic() {
            assertThat(TenantConfigKeyEnum.MESSAGE_BROKER_CONNECTION_SETTINGS.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.STATE_IT_SYSTEM_CONNECTION.isPublic()).isFalse();
            assertThat(TenantConfigKeyEnum.GLIFIC_MESSAGE_TEMPLATES.isPublic()).isFalse();
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
        }
    }

    @Nested
    @DisplayName("mandatory contract")
    class MandatoryTests {

        @Test
        @DisplayName("All keys are currently mandatory")
        void allKeys_areMandatory() {
            assertThat(Arrays.stream(TenantConfigKeyEnum.values())
                    .allMatch(TenantConfigKeyEnum::isMandatory)).isTrue();
        }

        @Test
        @DisplayName("getMandatoryKeys returns all enum values when all are mandatory")
        void getMandatoryKeys_returnsAllKeys() {
            EnumSet<TenantConfigKeyEnum> mandatory = TenantConfigKeyEnum.getMandatoryKeys();
            assertThat(mandatory).containsExactlyInAnyOrder(TenantConfigKeyEnum.values());
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
}
