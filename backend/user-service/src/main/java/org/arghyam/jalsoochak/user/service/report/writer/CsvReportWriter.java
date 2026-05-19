package org.arghyam.jalsoochak.user.service.report.writer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.service.report.ReportColumn;
import org.arghyam.jalsoochak.user.service.report.ReportSchema;
import org.arghyam.jalsoochak.user.service.report.ReportWriter;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** RFC 4180 CSV writer. Resource-agnostic. */
@Component
public class CsvReportWriter implements ReportWriter {

    @Override
    public ReportFormat format() {
        return ReportFormat.CSV;
    }

    @Override
    public <T> void write(ReportSchema<T> schema, Iterable<T> rows, OutputStream out) throws Exception {
        CSVFormat fmt = CSVFormat.DEFAULT.builder()
                .setHeader(schema.headers().toArray(new String[0]))
                .build();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        try (CSVPrinter printer = new CSVPrinter(writer, fmt)) {
            List<ReportColumn<T>> columns = schema.columns();
            int width = columns.size();
            for (T row : rows) {
                Object[] cells = new Object[width];
                for (int i = 0; i < width; i++) {
                    Object v = columns.get(i).extractor().apply(row);
                    cells[i] = v == null ? "" : v.toString();
                }
                printer.printRecord(cells);
            }
        }
    }
}
