package org.arghyam.jalsoochak.user.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserTypeLabel")
class UserTypeLabelTest {

    @ParameterizedTest
    @CsvSource({
            "PUMP_OPERATOR,PO",
            "SECTION_OFFICER,SO",
            "SUB_DIVISIONAL_OFFICER,SDO",
            "STATE_ADMIN,SA",
            "SUPER_STATE_ADMIN,SSA",
            "SUPER_USER,SU"
    })
    @DisplayName("initials each underscore-separated word of a multi-word role")
    void initialsMultiWordRoles(String cName, String expected) {
        assertThat(UserTypeLabel.shortLabel(cName)).isEqualTo(expected);
    }

    @Test
    @DisplayName("title-cases a single-word role, which has no meaningful initialism")
    void titleCasesSingleWordRole() {
        assertThat(UserTypeLabel.shortLabel("STAFF")).isEqualTo("Staff");
    }

    @ParameterizedTest
    @ValueSource(strings = {"section_officer", "Section Officer", "  SECTION_OFFICER  "})
    @DisplayName("normalises casing, spacing and surrounding whitespace")
    void normalisesInput(String cName) {
        assertThat(UserTypeLabel.shortLabel(cName)).isEqualTo("SO");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("returns null when there is no role to label")
    void returnsNullForBlank(String cName) {
        assertThat(UserTypeLabel.shortLabel(cName)).isNull();
    }

    @Test
    @DisplayName("labels a role the system does not define today rather than returning nothing")
    void labelsUnknownRole() {
        assertThat(UserTypeLabel.shortLabel("DISTRICT_WATER_ENGINEER")).isEqualTo("DWE");
    }
}
