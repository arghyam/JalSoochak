package org.arghyam.jalsoochak.tenant.enums.reportlayout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ReportKind")
class ReportKindTest {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "DAILY, SECTION_OFFICER, true",
            "DAILY, SUB_DIVISIONAL_OFFICER, false",
            "WEEKLY, SECTION_OFFICER, true",
            "WEEKLY, SUB_DIVISIONAL_OFFICER, true"
    })
    @DisplayName("message-service ships a built-in layout for every pair but the daily SDO report")
    void hasBuiltInLayout(ReportKind kind, ReportRole role, boolean expected) {
        assertThat(kind.hasBuiltInLayout(role)).isEqualTo(expected);
    }
}
