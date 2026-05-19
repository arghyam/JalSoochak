package org.arghyam.jalsoochak.user.service.report.writer;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.service.report.ReportColumn;
import org.arghyam.jalsoochak.user.service.report.ReportSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("XlsxReportWriter")
class XlsxReportWriterTest {

    private record Row(int id, String name) {}

    private static ReportSchema<Row> schema() {
        return new ReportSchema<>(List.of(
                new ReportColumn<>("ID", r -> r.id),
                new ReportColumn<>("Name", Row::name)));
    }

    private final XlsxReportWriter writer = new XlsxReportWriter();

    @Test
    @DisplayName("format() reports XLSX")
    void formatIsXlsx() {
        assertThat(writer.format()).isEqualTo(ReportFormat.XLSX);
    }

    @Test
    @DisplayName("produces a valid XLSX with bold header row + one row per record")
    void writesHeaderAndRows() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(schema(),
                List.of(new Row(1, "Anita"), new Row(2, "Bob")),
                out);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(3);

            org.apache.poi.ss.usermodel.Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("ID");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Name");
            assertThat(header.getCell(0).getCellStyle().getFontIndex()).isNotEqualTo((short) 0);

            org.apache.poi.ss.usermodel.Row r1 = sheet.getRow(1);
            assertThat(r1.getCell(0).getStringCellValue()).isEqualTo("1");
            assertThat(r1.getCell(1).getStringCellValue()).isEqualTo("Anita");

            org.apache.poi.ss.usermodel.Row r2 = sheet.getRow(2);
            assertThat(r2.getCell(1).getStringCellValue()).isEqualTo("Bob");
        }
    }

    @Test
    @DisplayName("empty rows still produce a header-only workbook")
    void emptyEmitsHeaderOnly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(schema(), List.<Row>of(), out);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(1);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("ID");
        }
    }

    @Test
    @DisplayName("null cell values are written as empty strings")
    void nullCellRendersBlank() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(schema(), List.of(new Row(1, null)), out);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("");
        }
    }
}
