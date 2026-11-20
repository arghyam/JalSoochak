package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One table column.
 *
 * @param field the catalogue column it shows
 * @param width a relative weight; either every column of a table sets one or none does, and then
 *              each column's catalogue default applies
 * @param <F>   the table's column catalogue
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ColumnSpecDTO<F extends Enum<F>>(F field, String label, Float width) {
}
