package org.arghyam.jalsoochak.tenant.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RegionTypeEnum")
class RegionTypeEnumTest {

    @Test
    @DisplayName("LGD has code 1")
    void lgd_hasCode1() {
        assertThat(RegionTypeEnum.LGD.getCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("DEPARTMENT has code 2")
    void department_hasCode2() {
        assertThat(RegionTypeEnum.DEPARTMENT.getCode()).isEqualTo(2);
    }

    @ParameterizedTest(name = "fromCode({0}.getCode()) == {0}")
    @EnumSource(RegionTypeEnum.class)
    @DisplayName("fromCode round-trips for every declared value")
    void fromCode_roundTrip_forAllValues(RegionTypeEnum regionType) {
        assertThat(RegionTypeEnum.fromCode(regionType.getCode())).isEqualTo(regionType);
    }

    @Test
    @DisplayName("fromCode throws IllegalArgumentException for unknown code")
    void fromCode_throwsForUnknownCode() {
        assertThatThrownBy(() -> RegionTypeEnum.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown RegionTypeEnum code: 99");
    }

    @Test
    @DisplayName("LGD and DEPARTMENT have distinct codes")
    void lgd_and_department_haveDistinctCodes() {
        assertThat(RegionTypeEnum.LGD.getCode()).isNotEqualTo(RegionTypeEnum.DEPARTMENT.getCode());
    }
}
