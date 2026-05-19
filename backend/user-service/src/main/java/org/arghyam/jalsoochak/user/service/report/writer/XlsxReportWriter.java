package org.arghyam.jalsoochak.user.service.report.writer;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.service.report.ReportColumn;
import org.arghyam.jalsoochak.user.service.report.ReportSchema;
import org.arghyam.jalsoochak.user.service.report.ReportWriter;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.List;

/**
 * XLSX writer backed by POI's {@link SXSSFWorkbook} (constant-memory streaming).
 * Resource-agnostic.
 */
@Component
public class XlsxReportWriter implements ReportWriter {

    private static final int ROW_ACCESS_WINDOW = 100;

    @Override
    public ReportFormat format() {
        return ReportFormat.XLSX;
    }

    @Override
    public <T> void write(ReportSchema<T> schema, Iterable<T> rows, OutputStream out) throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW)) {
            Sheet sheet = workbook.createSheet("Report");

            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            List<ReportColumn<T>> columns = schema.columns();
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns.get(i).header());
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (T row : rows) {
                Row r = sheet.createRow(rowIndex++);
                for (int i = 0; i < columns.size(); i++) {
                    Object v = columns.get(i).extractor().apply(row);
                    r.createCell(i).setCellValue(v == null ? "" : v.toString());
                }
            }
            workbook.write(out);
        }
    }
}
