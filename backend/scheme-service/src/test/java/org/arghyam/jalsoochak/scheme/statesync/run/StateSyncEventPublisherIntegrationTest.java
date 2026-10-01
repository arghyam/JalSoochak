package org.arghyam.jalsoochak.scheme.statesync.run;

import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.statesync.StateSyncIntegrationTestBase;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.SchemeReconciler.Reassignment;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncEventPublisher.PendingEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The SCHEME_DIMENSION_REPLACED fan-out, read back from real tenant tables. */
class StateSyncEventPublisherIntegrationTest extends StateSyncIntegrationTestBase {

    private KafkaProducer kafka;
    private StateSyncEventPublisher publisher;
    private int district;
    private int block;
    private int panchayat;
    private int village1;
    private int village2;
    private int zone;
    private int circle;
    private int division;
    private int subdivision1;
    private int subdivision2;

    @BeforeEach
    void setUp() {
        kafka = mock(KafkaProducer.class);
        when(kafka.publishJson(anyString(), any())).thenReturn(true);
        publisher = new StateSyncEventPublisher(kafka, new StateSyncTenantRepository(jdbc));
        district = lgd("Dibrugarh", 2, stateLgd, null);
        block = lgd("Tingkhong", 3, district, null);
        panchayat = lgd("Dillibari", 4, block, null);
        village1 = lgd("Chapatali No.2", 5, panchayat, null);
        village2 = lgd("Chapatali No.4", 5, panchayat, null);
        zone = dept("Upper Assam", 2, stateDept, null);
        circle = dept("Dibrugarh Circle", 3, zone, null);
        division = dept("Dibrugarh", 4, circle, null);
        subdivision1 = dept("Naharkatia", 5, division, null);
        subdivision2 = dept("Tingkhong SD", 5, division, null);
    }

    private void map(int schemeId, int lgdId, Integer deptId) {
        jdbc.update("INSERT INTO tenant_as.scheme_lgd_mapping_table (scheme_id, parent_lgd_id, parent_lgd_level, created_by, "
                + "updated_by) VALUES (?, ?, 'VILLAGE', ?, ?)", schemeId, lgdId, actor, actor);
        if (deptId != null) {
            jdbc.update("INSERT INTO tenant_as.scheme_department_mapping_table (scheme_id, parent_department_id, "
                    + "parent_department_level, created_by, updated_by) VALUES (?, ?, 'Sub-division', ?, ?)",
                    schemeId, deptId, actor, actor);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsOneRowPerVillageAndSubdivisionWithAncestorLevels() {
        int id = scheme("34123", "8165607", "CHAPATOLI PWSS", "SCH-000008");
        map(id, village1, subdivision1);
        map(id, village2, subdivision2);

        List<Map<String, Object>> events = publisher.schemeDimensionEvents(SCHEMA, TENANT_ID, List.of(id));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("eventType", "SCHEME_DIMENSION_REPLACED").containsEntry("schemeId", id)
                    .containsEntry("stateSchemeId", 34123).containsEntry("centreSchemeId", 8165607);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) e.get("rows");
            assertThat(rows).hasSize(4); // 2 villages x 2 sub-divisions
            assertThat(rows).filteredOn(r -> r.get("parentLgdLocationId").equals(village1)
                            && r.get("parentDepartmentLocationId").equals(subdivision2))
                    .singleElement().satisfies(r -> {
                        assertThat((List<Integer>) r.get("lgdLevels"))
                                .containsExactly(stateLgd, district, block, panchayat, village1, null);
                        assertThat((List<Integer>) r.get("deptLevels"))
                                .containsExactly(stateDept, zone, circle, division, subdivision2, null);
                    });
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSchemeWithoutASubdivisionSendsANullDepartment() {
        int id = scheme("1", "2", "S", null);
        map(id, village1, null);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) publisher
                .schemeDimensionEvents(SCHEMA, TENANT_ID, List.of(id)).get(0).get("rows");

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("parentDepartmentLocationId")).isNull();
            assertThat(r.get("deptLevels")).isNull();
        });
    }

    @Test
    void announcesTheRealSchemeBeforeTheReassignment() {
        int id = scheme("34123", "8165607", "CHAPATOLI PWSS", "SCH-000008");
        map(id, village1, subdivision1);

        publisher.publish(SCHEMA, TENANT_ID, new PendingEvents(List.of(id), List.of(), List.of(),
                List.of(new Reassignment(99, id))));

        InOrder order = inOrder(kafka);
        order.verify(kafka).publishJson(eq("scheme-service-topic"),
                argThat(e -> "SCHEME_DIMENSION_REPLACED".equals(((Map<?, ?>) e).get("eventType"))));
        ArgumentCaptor<Object> moved = ArgumentCaptor.forClass(Object.class);
        order.verify(kafka).publishJson(eq("scheme-service-topic"), moved.capture());
        assertThat((Map<String, Object>) moved.getValue()).containsEntry("eventType", "SCHEME_READINGS_REASSIGNED")
                .containsEntry("fromSchemeId", 99).containsEntry("toSchemeId", id).containsEntry("tenantId", TENANT_ID);
    }

    @Test
    void anUnparseableStateIdBecomesZero() {
        assertThat(StateSyncEventPublisher.safeParseInt("SS-1")).isZero();
        assertThat(Arrays.asList(StateSyncEventPublisher.safeParseInt(null), StateSyncEventPublisher.safeParseInt("7")))
                .containsExactly(0, 7);
    }
}
