package org.arghyam.jalsoochak.scheme.statesync.reconcile;

import org.arghyam.jalsoochak.scheme.statesync.StateSyncIntegrationTestBase;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamNode;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.HierarchyReconciler.DepartmentOutcome;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.HierarchyReconciler.DepartmentTree;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.HierarchyReconciler.LgdTree;
import org.arghyam.jalsoochak.scheme.statesync.run.SyncIssue;
import org.arghyam.jalsoochak.scheme.statesync.run.SyncReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HierarchyReconcilerIntegrationTest extends StateSyncIntegrationTestBase {

    private HierarchyReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new HierarchyReconciler(new StateSyncTenantRepository(jdbc));
    }

    private static DepartmentTree upstreamDepartments() {
        return new DepartmentTree(
                List.of(new UpstreamNode("ZON-001", "Lower Assam Zone", null)),
                List.of(new UpstreamNode("CIR-002", "Nalbari Circle", "ZON-001")),
                List.of(new UpstreamNode("DIV-010", "Bajali Division", "CIR-002")),
                List.of(new UpstreamNode("SDV-039", "Amguri", "DIV-010")));
    }

    @Test
    void matchesExistingDepartmentsByNameStampsCodesAndInsertsTheRest() {
        int zone = dept("Lower Assam", 2, stateDept, null);
        int division = dept("BAJALI", 4, null, null); // no circle yet, wrong parent

        SyncReport report = new SyncReport();
        DepartmentOutcome outcome = reconciler.reconcileDepartments(SCHEMA, upstreamDepartments(), actor, report);

        assertThat(outcome.idsByCode()).containsEntry("ZON-001", zone).containsEntry("DIV-010", division);
        int circle = outcome.idsByCode().get("CIR-002");
        assertThat(jdbc.queryForObject("SELECT state_dept_id FROM tenant_as.department_location_master_table WHERE id = ?",
                String.class, zone)).isEqualTo("ZON-001");
        assertThat(jdbc.queryForObject("SELECT parent_id FROM tenant_as.department_location_master_table WHERE id = ?",
                Integer.class, circle)).isEqualTo(zone);
        // Matched division keeps its title but moves under the upstream circle.
        assertThat(jdbc.queryForMap("SELECT title, parent_id FROM tenant_as.department_location_master_table WHERE id = ?",
                division)).containsEntry("title", "BAJALI").containsEntry("parent_id", circle);
        assertThat(report.get("department.inserted")).isEqualTo(2); // circle + sub-division
        assertThat(report.get("department.reparented")).isEqualTo(1);
        assertThat(outcome.touched()).contains(circle, division);
        assertThat(HierarchyReconciler.ancestorsByLevel(outcome.idsByCode().get("SDV-039"), outcome.nodesById()))
                .containsEntry(1, stateDept).containsEntry(2, zone).containsEntry(3, circle).containsEntry(4, division);
    }

    @Test
    void aSecondRunMatchesByCodeAndWritesNothing() {
        reconciler.reconcileDepartments(SCHEMA, upstreamDepartments(), actor, new SyncReport());
        int rows = count("SELECT COUNT(*) FROM tenant_as.department_location_master_table");

        SyncReport second = new SyncReport();
        DepartmentOutcome outcome = reconciler.reconcileDepartments(SCHEMA, upstreamDepartments(), actor, second);

        assertThat(count("SELECT COUNT(*) FROM tenant_as.department_location_master_table")).isEqualTo(rows);
        assertThat(second.get("department.matched_by_code")).isEqualTo(4);
        assertThat(second.get("department.inserted") + second.get("department.reparented") + second.get("department.code_stamped"))
                .isZero();
        assertThat(outcome.touched()).isEmpty();
    }

    @Test
    void twoSameNamedDepartmentsAreReportedNotGuessed() {
        dept("Bajali Division", 4, null, null);
        dept("Bajali Div", 4, null, null);

        SyncReport report = new SyncReport();
        reconciler.reconcileDepartments(SCHEMA, upstreamDepartments(), actor, report);

        assertThat(report.issues()).extracting(SyncIssue::category).contains("AMBIGUOUS_NAME");
        assertThat(report.issues()).filteredOn(i -> i.category().equals("PARENT_UNRESOLVED"))
                .extracting(SyncIssue::upstreamCode).containsExactly("SDV-039");
    }

    @Test
    void lgdNodesAreMatchedUnderTheirParentAndNeverCreated() {
        int district = lgd("Bajali", 2, stateLgd, null);
        int block = lgd("BAJALI (PART)", 3, district, null);
        int panchayat = lgd("Bamunkuchi", 4, block, null);
        int village = lgd("Raipur", 5, panchayat, null);
        lgd("Raipur", 5, lgd("Elsewhere", 4, block, null), null); // same name, other panchayat
        int before = count("SELECT COUNT(*) FROM tenant_as.lgd_location_master_table");

        SyncReport report = new SyncReport();
        Map<String, Integer> ids = reconciler.reconcileLgd(SCHEMA, new LgdTree(
                List.of(new UpstreamNode("DST-093", "Bajali", null)),
                List.of(new UpstreamNode("BLK-0010", "BAJALI (PART)", "DST-093")),
                List.of(new UpstreamNode("PAN-00103", "BAMUNKUCHI", "BLK-0010")),
                List.of(new UpstreamNode("VIL-000694", "RAIPUR", "PAN-00103"),
                        new UpstreamNode("VIL-000999", "NOWHERE GAON", "PAN-00103"))), actor, report);

        assertThat(ids).containsEntry("DST-093", district).containsEntry("BLK-0010", block)
                .containsEntry("PAN-00103", panchayat).containsEntry("VIL-000694", village);
        assertThat(jdbc.queryForObject("SELECT state_lgd_id FROM tenant_as.lgd_location_master_table WHERE id = ?",
                String.class, village)).isEqualTo("VIL-000694");
        assertThat(count("SELECT COUNT(*) FROM tenant_as.lgd_location_master_table")).isEqualTo(before);
        assertThat(report.issues()).singleElement().satisfies(i -> {
            assertThat(i.category()).isEqualTo("LGD_UNMATCHED");
            assertThat(i.upstreamCode()).isEqualTo("VIL-000999");
        });
    }

    @Test
    void anLgdParentDisagreementIsReportedNotApplied() {
        int district = lgd("Bajali", 2, stateLgd, "DST-093");
        int otherDistrict = lgd("Barpeta", 2, stateLgd, "DST-077");
        int block = lgd("Bhawanipur", 3, otherDistrict, "BLK-0250");

        SyncReport report = new SyncReport();
        reconciler.reconcileLgd(SCHEMA, new LgdTree(
                List.of(new UpstreamNode("DST-093", "Bajali", null), new UpstreamNode("DST-077", "Barpeta", null)),
                List.of(new UpstreamNode("BLK-0250", "BHAWANIPUR (BAJALI)", "DST-093")), List.of(), List.of()),
                actor, report);

        assertThat(report.issues()).extracting(SyncIssue::category).containsExactly("LGD_PARENT_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT parent_id FROM tenant_as.lgd_location_master_table WHERE id = ?",
                Integer.class, block)).isEqualTo(otherDistrict);
        assertThat(district).isNotEqualTo(otherDistrict);
    }
}
