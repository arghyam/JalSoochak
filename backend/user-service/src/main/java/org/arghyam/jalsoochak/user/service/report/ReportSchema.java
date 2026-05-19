package org.arghyam.jalsoochak.user.service.report;

import java.util.List;

/**
 * Column schema for a report: an ordered list of {@link ReportColumn}.
 * Writers consume this together with an {@code Iterable<T>} of rows to
 * produce a file in their own format.
 */
public record ReportSchema<T>(List<ReportColumn<T>> columns) {

    public ReportSchema {
        columns = List.copyOf(columns);
    }

    /** Header labels in column order — convenience for writers. */
    public List<String> headers() {
        return columns.stream().map(ReportColumn::header).toList();
    }
}
