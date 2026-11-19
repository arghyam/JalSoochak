package org.arghyam.jalsoochak.message.report.layout;

/**
 * One table column.
 *
 * @param field the catalogue column it shows
 * @param width a relative weight; either every column of a table sets one or none does, and then
 *              each column's catalogue default applies. {@code null} when absent
 * @param <F>   the table's column catalogue
 */
public record ColumnSpec<F extends Enum<F>>(F field, String label, Float width) {
}
