package org.arghyam.jalsoochak.tenant.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

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

        static Stream<Arguments> cases() {
            return Stream.of(
                    Arguments.of(TenantAccessRole.SUPER_USER,        true),
                    Arguments.of(TenantAccessRole.SUPER_STATE_ADMIN, true),
                    Arguments.of(TenantAccessRole.STATE_ADMIN,       false),
                    Arguments.of(TenantAccessRole.STAFF,             false)
            );
        }

        @ParameterizedTest(name = "{0}.isSuperUserEquivalent() == {1}")
        @MethodSource("cases")
        @DisplayName("returns expected boolean for each role")
        void isSuperUserEquivalent(TenantAccessRole role, boolean expected) {
            assertThat(role.isSuperUserEquivalent()).isEqualTo(expected);
        }
    }

    // ── isStateAdminEquivalent ────────────────────────────────────────────────────

    @Nested
    @DisplayName("isStateAdminEquivalent")
    class IsStateAdminEquivalent {

        static Stream<Arguments> cases() {
            return Stream.of(
                    Arguments.of(TenantAccessRole.STATE_ADMIN,       true),
                    Arguments.of(TenantAccessRole.SUPER_STATE_ADMIN, true),
                    Arguments.of(TenantAccessRole.SUPER_USER,        false),
                    Arguments.of(TenantAccessRole.STAFF,             false)
            );
        }

        @ParameterizedTest(name = "{0}.isStateAdminEquivalent() == {1}")
        @MethodSource("cases")
        @DisplayName("returns expected boolean for each role")
        void isStateAdminEquivalent(TenantAccessRole role, boolean expected) {
            assertThat(role.isStateAdminEquivalent()).isEqualTo(expected);
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
