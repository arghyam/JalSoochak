package org.arghyam.jalsoochak.tenant.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantStatusEnum")
class TenantStatusEnumTest {

    @Test
    @DisplayName("INACTIVE has code 0")
    void inactive_hasCode0() {
        assertThat(TenantStatusEnum.INACTIVE.getCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("ONBOARDED has code 1")
    void onboarded_hasCode1() {
        assertThat(TenantStatusEnum.ONBOARDED.getCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("CONFIGURED has code 2")
    void configured_hasCode2() {
        assertThat(TenantStatusEnum.CONFIGURED.getCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("ACTIVE has code 3")
    void active_hasCode3() {
        assertThat(TenantStatusEnum.ACTIVE.getCode()).isEqualTo(3);
    }

    @Test
    @DisplayName("SUSPENDED has code 4")
    void suspended_hasCode4() {
        assertThat(TenantStatusEnum.SUSPENDED.getCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("DEGRADED has code 5")
    void degraded_hasCode5() {
        assertThat(TenantStatusEnum.DEGRADED.getCode()).isEqualTo(5);
    }

    @Test
    @DisplayName("ARCHIVED has code 6")
    void archived_hasCode6() {
        assertThat(TenantStatusEnum.ARCHIVED.getCode()).isEqualTo(6);
    }

    @ParameterizedTest(name = "fromCode({0}.getCode()) == {0}")
    @EnumSource(TenantStatusEnum.class)
    @DisplayName("fromCode round-trips for every declared value")
    void fromCode_roundTrip_forAllValues(TenantStatusEnum status) {
        assertThat(TenantStatusEnum.fromCode(status.getCode())).isEqualTo(status);
    }

    @Test
    @DisplayName("fromCode throws IllegalArgumentException for unknown code")
    void fromCode_throwsForUnknownCode() {
        assertThatThrownBy(() -> TenantStatusEnum.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown TenantStatusEnum code: 99");
    }

    @Test
    @DisplayName("all codes are unique across enum values")
    void allCodes_areUnique() {
        long distinctCodes = java.util.Arrays.stream(TenantStatusEnum.values())
                .mapToInt(TenantStatusEnum::getCode)
                .distinct()
                .count();
        assertThat(distinctCodes).isEqualTo(TenantStatusEnum.values().length);
    }
}
