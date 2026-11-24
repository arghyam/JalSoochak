package org.arghyam.jalsoochak.message.report;

import org.arghyam.jalsoochak.message.report.catalog.SchemeColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.layout.ColumnSpec;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.layout.ReportSection;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a layout draws that has to be looked up before it can be rendered: which scheme lists, how
 * much detail each needs, and whether the officer-performance rows are drawn.
 *
 * <p>Lets the caller resolve only what is drawn. Each lookup is one or more queries over every
 * scheme in the list, so a list nobody sees is pure cost.</p>
 *
 * @param schemeLists each list the layout draws, with the extra detail its columns need; unmodifiable
 * @param officerRows whether the layout draws an {@code OFFICER_PERFORMANCE} section
 */
public record ReportDataNeeds(Map<SchemeList, SchemeDetail> schemeLists, boolean officerRows) {

    /**
     * The per-scheme lookups beyond the name, IMIS id and pump operators every row carries.
     *
     * @param sectionOfficers the schemes' Section Officer names and mobiles
     * @param villages        the villages the schemes serve
     */
    public record SchemeDetail(boolean sectionOfficers, boolean villages) {
    }

    public ReportDataNeeds {
        schemeLists = Map.copyOf(schemeLists);
    }

    /** Derives the needs from what the layout's sections draw. */
    public static ReportDataNeeds of(ReportLayout layout) {
        Map<SchemeList, SchemeDetail> lists = new EnumMap<>(SchemeList.class);
        boolean officerRows = false;
        for (ReportSection section : layout.sections()) {
            switch (section) {
                case ReportSection.SchemeListSection list when list.list() != null ->
                        lists.merge(list.list(), detail(list), ReportDataNeeds::union);
                case ReportSection.OfficerPerformanceSection officers -> officerRows = true;
                default -> {
                    // A summary draws analytics' figures as they arrive.
                }
            }
        }
        return new ReportDataNeeds(lists, officerRows);
    }

    /** The detail {@code list} needs; empty when the layout does not draw it. */
    public Optional<SchemeDetail> schemeList(SchemeList list) {
        return Optional.ofNullable(schemeLists.get(list));
    }

    private static SchemeDetail detail(ReportSection.SchemeListSection section) {
        boolean sectionOfficers = false;
        boolean villages = false;
        for (ColumnSpec<SchemeColumn> column : section.columns()) {
            SchemeColumn field = column.field();
            if (field != null) {
                sectionOfficers |= field.needsSectionOfficers();
                villages |= field.needsVillages();
            }
        }
        return new SchemeDetail(sectionOfficers, villages);
    }

    /** Validation allows each list once; a repeated one still gets everything either copy draws. */
    private static SchemeDetail union(SchemeDetail a, SchemeDetail b) {
        return new SchemeDetail(a.sectionOfficers() || b.sectionOfficers(), a.villages() || b.villages());
    }
}
