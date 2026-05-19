package org.arghyam.jalsoochak.user.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReportFormat")
class ReportFormatTest {

    @Test
    @DisplayName("key / extension / contentType are stable for CSV")
    void csvAttributes() {
        assertThat(ReportFormat.CSV.key()).isEqualTo("CSV");
        assertThat(ReportFormat.CSV.extension()).isEqualTo("csv");
        assertThat(ReportFormat.CSV.contentType()).isEqualTo("text/csv");
    }

    @Test
    @DisplayName("key / extension / contentType are stable for XLSX")
    void xlsxAttributes() {
        assertThat(ReportFormat.XLSX.key()).isEqualTo("XLSX");
        assertThat(ReportFormat.XLSX.extension()).isEqualTo("xlsx");
        assertThat(ReportFormat.XLSX.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    @DisplayName("fromString is case-insensitive and trims whitespace")
    void fromStringNormalizes() {
        assertThat(ReportFormat.fromString("csv")).isEqualTo(ReportFormat.CSV);
        assertThat(ReportFormat.fromString(" CSV ")).isEqualTo(ReportFormat.CSV);
        assertThat(ReportFormat.fromString("xlsx")).isEqualTo(ReportFormat.XLSX);
        assertThat(ReportFormat.fromString("Xlsx")).isEqualTo(ReportFormat.XLSX);
    }

    @Test
    @DisplayName("fromString rejects null and blank")
    void rejectsBlank() {
        assertThatThrownBy(() -> ReportFormat.fromString(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format is required");
        assertThatThrownBy(() -> ReportFormat.fromString("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fromString rejects unknown formats with the list of valid ones in the message")
    void rejectsUnknown() {
        assertThatThrownBy(() -> ReportFormat.fromString("pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported report format: pdf")
                .hasMessageContaining("CSV")
                .hasMessageContaining("XLSX");
    }
}
