package org.arghyam.jalsoochak.user.service.report;

import java.util.function.Function;

/**
 * One column in a {@link ReportSchema}: a header label and an extractor
 * that pulls the cell value from a row of type {@code T}.
 *
 * @param header    column header, written by writers as the first row
 * @param extractor function applied to each row to produce the cell value;
 *                  the writer is responsible for null-safe stringification
 */
public record ReportColumn<T>(String header, Function<T, ?> extractor) {
    public ReportColumn {
        if (header == null) throw new NullPointerException("header must not be null");
        if (extractor == null) throw new NullPointerException("extractor must not be null");
    }
}
