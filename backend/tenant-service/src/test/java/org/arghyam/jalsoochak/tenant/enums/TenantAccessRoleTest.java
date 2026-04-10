package org.arghyam.jalsoochak.tenant.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantAccessRole")
class TenantAccessRoleTest {

    // ── fromCName – canonical forms ──────────────────────────────────────────────

    @Nested
    @DisplayName("fromCName – known roles")
    class FromCNameKnownRoles {

        @ParameterizedTest(name = "\"{0}\" → SUPER_USER")
        @ValueSource(strings = {"SUPER_USER", "super_user", "Super_User", "Super User", "super user"})
        @DisplayName("maps SUPER_USER variants to SUPER_USER")
        void fromCName_mapsToSuperUser(String cName) {
            assertThat(TenantAccessRole.fromCName(cName)).isEqualTo(TenantAccessRole.SUPER_USER);
        }

        @ParameterizedTest(name = "\"{0}\" → STATE_ADMIN")
        @ValueSource(strings = {"STATE_ADMIN", "state_admin", "State_Admin", "State Admin"})
        @DisplayName("maps STATE_ADMIN variants to STATE_ADMIN")
        void fromCName_mapsToStateAdmin(String cName) {
            assertThat(TenantAccessRole.fromCName(cName)).isEqualTo(TenantAccessRole.STATE_ADMIN);
        }

        @ParameterizedTest(name = "\"{0}\" → SUPER_STATE_ADMIN")
        @ValueSource(strings = {"SUPER_STATE_ADMIN", "super_state_admin", "Super State Admin"})
        @DisplayName("maps SUPER_STATE_ADMIN variants to SUPER_STATE_ADMIN")
        void fromCName_mapsToSuperStateAdmin(String cName) {
            assertThat(TenantAccessRole.fromCName(cName)).isEqualTo(TenantAccessRole.SUPER_STATE_ADMIN);
        }

        @ParameterizedTest(name = "\"{0}\" → STAFF")
        @ValueSource(strings = {"STAFF", "staff", "Staff"})
        @DisplayName("maps STAFF variants to STAFF")
        void fromCName_mapsToStaff(String cName) {
            assertThat(TenantAccessRole.fromCName(cName)).isEqualTo(TenantAccessRole.STAFF);
        }

        @Test
        @DisplayName("leading and trailing whitespace is trimmed before matching")
        void fromCName_trimsWhitespace() {
            assertThat(TenantAccessRole.fromCName("  STAFF  ")).isEqualTo(TenantAccessRole.STAFF);
        }
    }

    // ── fromCName – error cases ───────────────────────────────────────────────────

    @Nested
    @DisplayName("fromCName – error cases")
    class FromCNameErrors {

        @Test
        @DisplayName("throws IllegalArgumentException for null cName")
        void fromCName_throwsForNull() {
            assertThatThrownBy(() -> TenantAccessRole.fromCName(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
        }

        @ParameterizedTest(name = "\"{0}\" is unrecognised")
        @ValueSource(strings = {"UNKNOWN", "PUMP_OPERATOR", "", "SECTION_OFFICER"})
        @DisplayName("throws IllegalArgumentException for unrecognised cName values")
        void fromCName_throwsForUnrecognised(String cName) {
            assertThatThrownBy(() -> TenantAccessRole.fromCName(cName))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unrecognised user type c_name");
        }
    }

    // ── isSuperUserEquivalent ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("isSuperUserEquivalent")
    class IsSuperUserEquivalent {

        @Test
        @DisplayName("SUPER_USER is super-user equivalent")
        void superUser_isSuperUserEquivalent() {
            assertThat(TenantAccessRole.SUPER_USER.isSuperUserEquivalent()).isTrue();
        }

        @Test
        @DisplayName("SUPER_STATE_ADMIN is super-user equivalent")
        void superStateAdmin_isSuperUserEquivalent() {
            assertThat(TenantAccessRole.SUPER_STATE_ADMIN.isSuperUserEquivalent()).isTrue();
        }

        @Test
        @DisplayName("STATE_ADMIN is not super-user equivalent")
        void stateAdmin_isNotSuperUserEquivalent() {
            assertThat(TenantAccessRole.STATE_ADMIN.isSuperUserEquivalent()).isFalse();
        }

        @Test
        @DisplayName("STAFF is not super-user equivalent")
        void staff_isNotSuperUserEquivalent() {
            assertThat(TenantAccessRole.STAFF.isSuperUserEquivalent()).isFalse();
        }
    }

    // ── isStateAdminEquivalent ────────────────────────────────────────────────────

    @Nested
    @DisplayName("isStateAdminEquivalent")
    class IsStateAdminEquivalent {

        @Test
        @DisplayName("STATE_ADMIN is state-admin equivalent")
        void stateAdmin_isStateAdminEquivalent() {
            assertThat(TenantAccessRole.STATE_ADMIN.isStateAdminEquivalent()).isTrue();
        }

        @Test
        @DisplayName("SUPER_STATE_ADMIN is state-admin equivalent")
        void superStateAdmin_isStateAdminEquivalent() {
            assertThat(TenantAccessRole.SUPER_STATE_ADMIN.isStateAdminEquivalent()).isTrue();
        }

        @Test
        @DisplayName("SUPER_USER is not state-admin equivalent")
        void superUser_isNotStateAdminEquivalent() {
            assertThat(TenantAccessRole.SUPER_USER.isStateAdminEquivalent()).isFalse();
        }

        @Test
        @DisplayName("STAFF is not state-admin equivalent")
        void staff_isNotStateAdminEquivalent() {
            assertThat(TenantAccessRole.STAFF.isStateAdminEquivalent()).isFalse();
        }
    }

    // ── SUPER_STATE_ADMIN dual-role behaviour ─────────────────────────────────────

    @Test
    @DisplayName("SUPER_STATE_ADMIN is both super-user and state-admin equivalent")
    void superStateAdmin_isBothEquivalents() {
        assertThat(TenantAccessRole.SUPER_STATE_ADMIN.isSuperUserEquivalent()).isTrue();
        assertThat(TenantAccessRole.SUPER_STATE_ADMIN.isStateAdminEquivalent()).isTrue();
    }
}
