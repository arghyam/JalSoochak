package org.arghyam.jalsoochak.user.service.report.writer;

import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.service.report.ReportColumn;
import org.arghyam.jalsoochak.user.service.report.ReportSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CsvReportWriter")
class CsvReportWriterTest {

    private record Row(int id, String name, String email) {}

    private static ReportSchema<Row> schema() {
        return new ReportSchema<>(List.of(
                new ReportColumn<>("ID", r -> r.id),
                new ReportColumn<>("Name", Row::name),
                new ReportColumn<>("Email", Row::email)));
    }

    private final CsvReportWriter writer = new CsvReportWriter();

    @Test
    @DisplayName("format() reports CSV")
    void formatIsCsv() {
        assertThat(writer.format()).isEqualTo(ReportFormat.CSV);
    }

    @Test
    @DisplayName("writes RFC 4180 header followed by one record per row")
    void writesHeaderAndRows() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(schema(),
                List.of(new Row(1, "Anita", "a@x"), new Row(2, "Bob", "b@x")),
                out);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv.lines()).containsExactly(
                "ID,Name,Email",
                "1,Anita,a@x",
                "2,Bob,b@x");
    }

    @Test
    @DisplayName("empty row collection still emits the header row")
    void emptyEmitsHeaderOnly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(schema(), List.<Row>of(), out);

        assertThat(out.toString(StandardCharsets.UTF_8).strip()).isEqualTo("ID,Name,Email");
    }

    @Test
    @DisplayName("null cell extractor result is rendered as empty string")
    void nullCellRendersBlank() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(schema(),
                List.of(new Row(1, null, "a@x")),
                out);

        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("1,,a@x");
    }

    @Test
    @DisplayName("quotes values that contain commas, quotes, or newlines per RFC 4180")
    void quotesSpecialCharacters() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(schema(),
                List.of(new Row(1, "Sharma, A.", "needs \"escape\"")),
                out);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"Sharma, A.\"");
        assertThat(csv).contains("\"needs \"\"escape\"\"\"");
    }
}
