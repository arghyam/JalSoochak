package org.arghyam.jalsoochak.user.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Enums")
class EnumsTest {

    // ── TenantUserStatus ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("TenantUserStatus")
    class TenantUserStatusTest {

        @Test
        @DisplayName("fromCode(0) returns INACTIVE")
        void fromCodeZeroReturnsInactive() {
            assertThat(TenantUserStatus.fromCode(0)).isEqualTo(TenantUserStatus.INACTIVE);
        }

        @Test
        @DisplayName("fromCode(1) returns ACTIVE")
        void fromCodeOneReturnsActive() {
            assertThat(TenantUserStatus.fromCode(1)).isEqualTo(TenantUserStatus.ACTIVE);
        }

        @Test
        @DisplayName("fromCode throws IllegalArgumentException for unknown code")
        void fromCodeThrowsForUnknownCode() {
            assertThatThrownBy(() -> TenantUserStatus.fromCode(99))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("code field matches ordinal values")
        void codeFieldMatchesExpectedValues() {
            assertThat(TenantUserStatus.INACTIVE.code).isZero();
            assertThat(TenantUserStatus.ACTIVE.code).isEqualTo(1);
        }
    }

    // ── AdminUserStatus ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("AdminUserStatus")
    class AdminUserStatusTest {

        @Test
        @DisplayName("fromCode(0) returns INACTIVE")
        void fromCodeZeroReturnsInactive() {
            assertThat(AdminUserStatus.fromCode(0)).isEqualTo(AdminUserStatus.INACTIVE);
        }

        @Test
        @DisplayName("fromCode(1) returns ACTIVE")
        void fromCodeOneReturnsActive() {
            assertThat(AdminUserStatus.fromCode(1)).isEqualTo(AdminUserStatus.ACTIVE);
        }

        @Test
        @DisplayName("fromCode(2) returns PENDING")
        void fromCodeTwoReturnsPending() {
            assertThat(AdminUserStatus.fromCode(2)).isEqualTo(AdminUserStatus.PENDING);
        }

        @Test
        @DisplayName("fromCode throws IllegalArgumentException for unknown code")
        void fromCodeThrowsForUnknownCode() {
            assertThatThrownBy(() -> AdminUserStatus.fromCode(99))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("code field matches expected values")
        void codeFieldMatchesExpectedValues() {
            assertThat(AdminUserStatus.INACTIVE.code).isZero();
            assertThat(AdminUserStatus.ACTIVE.code).isEqualTo(1);
            assertThat(AdminUserStatus.PENDING.code).isEqualTo(2);
        }
    }

    // ── TenantAccessRole ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("TenantAccessRole")
    class TenantAccessRoleTest {

        @Test
        @DisplayName("isSuperUserEquivalent returns true only for SUPER_USER and SUPER_STATE_ADMIN")
        void isSuperUserEquivalent() {
            assertThat(TenantAccessRole.SUPER_USER.isSuperUserEquivalent()).isTrue();
            assertThat(TenantAccessRole.SUPER_STATE_ADMIN.isSuperUserEquivalent()).isTrue();
            assertThat(TenantAccessRole.STATE_ADMIN.isSuperUserEquivalent()).isFalse();
            assertThat(TenantAccessRole.STAFF.isSuperUserEquivalent()).isFalse();
        }

        @Test
        @DisplayName("isStateAdminEquivalent returns true only for STATE_ADMIN and SUPER_STATE_ADMIN")
        void isStateAdminEquivalent() {
            assertThat(TenantAccessRole.STATE_ADMIN.isStateAdminEquivalent()).isTrue();
            assertThat(TenantAccessRole.SUPER_STATE_ADMIN.isStateAdminEquivalent()).isTrue();
            assertThat(TenantAccessRole.SUPER_USER.isStateAdminEquivalent()).isFalse();
            assertThat(TenantAccessRole.STAFF.isStateAdminEquivalent()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"SUPER_USER", "super_user", "Super User", "SUPER USER"})
        @DisplayName("fromCName resolves SUPER_USER for various casings and spacings")
        void fromCNameResolvesSuperUser(String cName) {
            assertThat(TenantAccessRole.fromCName(cName)).isEqualTo(TenantAccessRole.SUPER_USER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"STATE_ADMIN", "state_admin", "State Admin"})
        @DisplayName("fromCName resolves STATE_ADMIN for various formats")
        void fromCNameResolvesStateAdmin(String cName) {
            assertThat(TenantAccessRole.fromCName(cName)).isEqualTo(TenantAccessRole.STATE_ADMIN);
        }

        @ParameterizedTest
        @ValueSource(strings = {"SUPER_STATE_ADMIN", "super_state_admin"})
        @DisplayName("fromCName resolves SUPER_STATE_ADMIN")
        void fromCNameResolvesSuperStateAdmin(String cName) {
            assertThat(TenantAccessRole.fromCName(cName)).isEqualTo(TenantAccessRole.SUPER_STATE_ADMIN);
        }

        @Test
        @DisplayName("fromCName resolves STAFF")
        void fromCNameResolvesStaff() {
            assertThat(TenantAccessRole.fromCName("STAFF")).isEqualTo(TenantAccessRole.STAFF);
        }

        @Test
        @DisplayName("fromCName throws IllegalArgumentException for null")
        void fromCNameThrowsForNull() {
            assertThatThrownBy(() -> TenantAccessRole.fromCName(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("fromCName throws IllegalArgumentException for unrecognised c_name")
        void fromCNameThrowsForUnrecognised() {
            assertThatThrownBy(() -> TenantAccessRole.fromCName("UNKNOWN_ROLE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("UNKNOWN_ROLE");
        }
    }

    // ── OtpType ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("OtpType")
    class OtpTypeTest {

        @Test
        @DisplayName("enum has expected constants")
        void hasExpectedConstants() {
            assertThat(OtpType.values()).containsExactlyInAnyOrder(OtpType.LOGIN, OtpType.PASSWORD_CHANGE);
        }

        @Test
        @DisplayName("valueOf returns correct constant by name")
        void valueOfReturnsCorrectConstant() {
            assertThat(OtpType.valueOf("LOGIN")).isEqualTo(OtpType.LOGIN);
            assertThat(OtpType.valueOf("PASSWORD_CHANGE")).isEqualTo(OtpType.PASSWORD_CHANGE);
        }
    }
}
