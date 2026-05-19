package org.arghyam.jalsoochak.user.service.report;

import org.arghyam.jalsoochak.user.enums.ReportFormat;

import java.io.OutputStream;

/**
 * Format-only writer. Given a {@link ReportSchema} and an iterable of rows,
 * writes the header followed by one record per row to {@code out}.
 *
 * <p>Writers are <strong>resource-agnostic</strong>: the same {@code CsvReportWriter}
 * serves staff exports, scheme exports, and every future report type.
 * Adding a new report type does <em>not</em> require a new writer.
 */
public interface ReportWriter {

    ReportFormat format();

    /**
     * Writes header + rows to {@code out}. The caller owns the stream and
     * is responsible for closing it.
     */
    <T> void write(ReportSchema<T> schema, Iterable<T> rows, OutputStream out) throws Exception;
}
