package org.arghyam.jalsoochak.tenant.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StatusEnum")
class StatusEnumTest {

    @Test
    @DisplayName("ACTIVE has code 1")
    void active_hasCode1() {
        assertThat(StatusEnum.ACTIVE.getCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("INACTIVE has code 0")
    void inactive_hasCode0() {
        assertThat(StatusEnum.INACTIVE.getCode()).isEqualTo(0);
    }

    @ParameterizedTest(name = "fromCode({0}.getCode()) == {0}")
    @EnumSource(StatusEnum.class)
    @DisplayName("fromCode round-trips for every declared value")
    void fromCode_roundTrip_forAllValues(StatusEnum status) {
        assertThat(StatusEnum.fromCode(status.getCode())).isEqualTo(status);
    }

    @Test
    @DisplayName("fromCode throws IllegalArgumentException for unknown code")
    void fromCode_throwsForUnknownCode() {
        assertThatThrownBy(() -> StatusEnum.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown StatusEnum code: 99");
    }

    @Test
    @DisplayName("ACTIVE and INACTIVE have distinct codes")
    void active_and_inactive_haveDistinctCodes() {
        assertThat(StatusEnum.ACTIVE.getCode()).isNotEqualTo(StatusEnum.INACTIVE.getCode());
    }
}
