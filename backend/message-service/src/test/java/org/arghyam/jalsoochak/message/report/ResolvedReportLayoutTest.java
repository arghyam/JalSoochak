package org.arghyam.jalsoochak.message.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.render.ReportFormats;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ResolvedReportLayoutTest {

    private static final ReportLayout LAYOUT =
            new DefaultReportLayouts(new ObjectMapper()).builtIn(ReportKind.DAILY, "SECTION_OFFICER").orElseThrow();

    @Test
    void anUnsetUrlOrPhoneReadsAsBlank() {
        ResolvedReportLayout resolved = new ResolvedReportLayout(LAYOUT, ReportFormats.defaults(), null, null);

        assertThat(resolved.dashboardUrl()).isEmpty();
        assertThat(resolved.supportPhone()).isEmpty();
    }

    @Test
    void theLayoutAndFormatsAreRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ResolvedReportLayout(null, ReportFormats.defaults(), "", ""));
        assertThatNullPointerException().isThrownBy(() -> new ResolvedReportLayout(LAYOUT, null, "", ""));
    }

    @Test
    void theNeedsAreTheLayouts() {
        ResolvedReportLayout resolved = new ResolvedReportLayout(LAYOUT, ReportFormats.defaults(), "", "");

        assertThat(resolved.needs()).isEqualTo(ReportDataNeeds.of(LAYOUT));
    }
}
