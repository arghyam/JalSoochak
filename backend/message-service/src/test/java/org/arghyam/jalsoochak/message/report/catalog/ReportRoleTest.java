package org.arghyam.jalsoochak.message.report.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportRoleTest {

    @Test
    void readsTheRoleIgnoringCaseAndSurroundingBlanks() {
        assertThat(ReportRole.fromUserType("SECTION_OFFICER")).contains(ReportRole.SECTION_OFFICER);
        assertThat(ReportRole.fromUserType(" sub_divisional_officer ")).contains(ReportRole.SUB_DIVISIONAL_OFFICER);
    }

    @Test
    void anyOtherUserTypeIsNoReportRole() {
        assertThat(ReportRole.fromUserType(null)).isEmpty();
        assertThat(ReportRole.fromUserType("")).isEmpty();
        assertThat(ReportRole.fromUserType("PUMP_OPERATOR")).isEmpty();
    }
}
