package org.arghyam.jalsoochak.scheme.statesync.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.statesync.StateSyncIntegrationTestBase;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties.Mode;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamNode;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamPerson;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamScheme;
import org.arghyam.jalsoochak.scheme.statesync.port.StateMasterDataException;
import org.arghyam.jalsoochak.scheme.statesync.port.StateMasterDataSource;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.HierarchyReconciler;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncRunner.RunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link StateSyncRunner} end to end: fake upstream, real PostgreSQL, mocked Kafka. */
class StateSyncRunnerIntegrationTest extends StateSyncIntegrationTestBase {

    private FakeSource source;
    private KafkaProducer kafka;
    private StateSyncRunRepository runRepository;
    private StateSyncRunner runner;

    @BeforeEach
    void setUp() {
        source = new FakeSource();
        kafka = mock(KafkaProducer.class);
        when(kafka.publishJson(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        runRepository = new StateSyncRunRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        StateSyncTenantRepository tenantRepository = new StateSyncTenantRepository(jdbc);
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("source", source));
        runner = new StateSyncRunner(properties, beans.getBeanProvider(StateMasterDataSource.class), runRepository,
                tenantRepository, new HierarchyReconciler(tenantRepository), pii,
                new StateSyncEventPublisher(kafka, new SchemeDbRepository(jdbc)),
                new DataSourceTransactionManager(dataSource));

        int district = lgd("Dibrugarh", 2, stateLgd, null);
        int block = lgd("Tingkhong", 3, district, null);
        int panchayat = lgd("Dillibari", 4, block, null);
        lgd("Chapatali No.2", 5, panchayat, null);

        source.zones.add(new UpstreamNode("ZON-003", "Upper Assam Zone", null));
        source.circles.add(new UpstreamNode("CIR-005", "Dibrugarh Circle", "ZON-003"));
        source.divisions.add(new UpstreamNode("DIV-020", "Dibrugarh Division", "CIR-005"));
        source.subdivisions.add(new UpstreamNode("SDV-035", "Naharkatia", "DIV-020"));
        source.districts.add(new UpstreamNode("DST-061", "Dibrugarh", null));
        source.blocks.add(new UpstreamNode("BLK-0089", "TINGKHONG", "DST-061"));
        source.panchayats.add(new UpstreamNode("PAN-00994", "DILLIBARI", "BLK-0089"));
        source.villages.add(new UpstreamNode("VIL-008633", "CHAPATALI NO.2", "PAN-00994"));
        source.users.add(new UpstreamPerson("USR-016363", "Thagen Saikia", "9100000001", "section-officer"));
        source.users.add(new UpstreamPerson("USR-000001", "A Khalasi", "9100000009", "khalasi"));
        source.schemes.add(scheme(LocalDateTime.of(2026, 9, 20, 11, 47, 13)));
    }

    private static UpstreamScheme scheme(LocalDateTime updatedAt) {
        return new UpstreamScheme("SCH-000008", "8165607", "34123", "CHAPATOLI PWSS", "handed-over", "non-operative",
                280, 241, "27.154296", "95.164694", List.of("SDV-035"), List.of("VIL-008633"),
                List.of(new UpstreamPerson("USR-016363", "Thagen Saikia", "9100000001", "section-officer"),
                        new UpstreamPerson("USR-021920", "Bolin Gogoi", "9100000002", "jal-mitra")),
                updatedAt);
    }

    @Test
    void aDryRunReportsEverythingAndWritesNothing() {
        properties.setMode(Mode.DRY_RUN);

        RunResult result = runner.runNow(RunKind.FULL, null, "SCHEDULER").orElseThrow();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.counts()).containsEntry("schemes.inserted", 1).containsEntry("department.inserted", 4)
                .containsEntry("users.role_not_ingested", 1).containsEntry("fetched.villages", 1);
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_master_table")).isZero();
        assertThat(count("SELECT COUNT(*) FROM tenant_as.department_location_master_table")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tenant_as.lgd_location_master_table WHERE state_lgd_id IS NOT NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM tenant_as.user_table")).isEqualTo(1);
        verify(kafka, never()).publishJson(anyString(), org.mockito.ArgumentMatchers.any());
        assertThat(runRepository.listRuns(TENANT_ID, 1)).singleElement()
                .satisfies(r -> assertThat(r.mode()).isEqualTo("DRY_RUN"));
    }

    @Test
    void anApplyRunWritesAndAnnouncesAndAdvancesTheWatermark() {
        properties.setMode(Mode.APPLY);

        RunResult result = runner.runNow(RunKind.FULL, null, "SCHEDULER").orElseThrow();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.issues()).isEmpty();
        int schemeId = jdbc.queryForObject("SELECT id FROM tenant_as.scheme_master_table WHERE state_scheme_code = 'SCH-000008'", Integer.class);
        assertThat(count("SELECT COUNT(*) FROM tenant_as.user_scheme_mapping_table WHERE scheme_id = ? AND status = 1", schemeId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM tenant_as.scheme_department_mapping_table WHERE scheme_id = ?", schemeId)).isEqualTo(1);
        assertThat(runRepository.lastAppliedWatermark(TENANT_ID)).contains(LocalDateTime.of(2026, 9, 20, 11, 47, 13));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(kafka, atLeastOnce()).publishJson(eq("scheme-service-topic"), events.capture());
        assertThat(events.getAllValues()).extracting(e -> String.valueOf(((Map<?, ?>) e).get("eventType")))
                .contains("SCHEME_UPDATED", "DEPARTMENT_LOCATION_UPDATED");
        verify(kafka, atLeastOnce()).publishJson(eq("user-service-topic"), events.capture());
        assertThat(events.getAllValues()).extracting(e -> String.valueOf(((Map<?, ?>) e).get("eventType")))
                .contains("USER_CREATED", "USER_SCHEME_MAPPINGS_REPLACED");
        assertThat(events.getAllValues()).noneMatch(e -> e.toString().contains("9100000001"));
    }

    @Test
    void aDeltaAsksOnlyForSchemesSinceTheWatermarkMinusTheOverlap() {
        properties.setMode(Mode.APPLY);
        runner.runNow(RunKind.FULL, null, "SCHEDULER").orElseThrow();

        runner.runNow(RunKind.DELTA, null, "SCHEDULER").orElseThrow();

        assertThat(source.lastUpdatedSince).isEqualTo(LocalDateTime.of(2026, 9, 20, 11, 17, 13));
    }

    @Test
    void aDeltaResolvesALenientIngestionPlaceholderByItsImisId() {
        properties.setMode(Mode.APPLY);
        int placeholder = jdbc.queryForObject("INSERT INTO tenant_as.scheme_master_table (state_scheme_id, centre_scheme_id, "
                + "scheme_name, work_status, operating_status, is_auto_provisioned) VALUES ('', '8165607', "
                + "'Auto-provisioned scheme (centre:8165607)', 0, 0, TRUE) RETURNING id", Integer.class);
        jdbc.update("INSERT INTO tenant_as.scheme_master_table (state_scheme_id, centre_scheme_id, scheme_name, "
                + "work_status, operating_status, is_auto_provisioned) VALUES ('', '777', 'Auto-provisioned scheme', 0, 0, TRUE)");
        // Nothing changed upstream since the watermark: only the placeholder look-up can find the scheme.
        source.deltaReturnsNothing = true;

        RunResult first = runner.runNow(RunKind.DELTA, null, "SCHEDULER").orElseThrow();

        assertThat(first.counts()).containsEntry("placeholders.looked_up", 2).containsEntry("placeholders.found_upstream", 1)
                .containsEntry("placeholders.unknown_upstream", 1).containsEntry("schemes.inserted", 1);
        assertThat(first.issues()).filteredOn(i -> i.category().equals("PLACEHOLDER_SUPERSEDED"))
                .singleElement().satisfies(i -> assertThat(i.detail()).containsEntry("placeholderSchemeId", placeholder));

        // Resolved, and the unknown one is in its cool-down: the next delta asks about neither.
        RunResult second = runner.runNow(RunKind.DELTA, null, "SCHEDULER").orElseThrow();
        assertThat(second.counts()).doesNotContainKey("placeholders.looked_up");
    }

    @Test
    void anUpstreamFailureFailsTheRunAndWritesNothing() {
        properties.setMode(Mode.APPLY);
        source.failVillages = true;

        RunResult result = runner.runNow(RunKind.FULL, null, "SCHEDULER").orElseThrow();

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).contains("upstream down");
        assertThat(count("SELECT COUNT(*) FROM tenant_as.department_location_master_table")).isEqualTo(1);
        assertThat(runRepository.listRuns(TENANT_ID, 1)).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo("FAILED"));
        // The failed run released the lock.
        assertThat(runner.runNow(RunKind.DELTA, null, "SCHEDULER")).isPresent();
    }

    @Test
    void aRunIsSkippedWhileAnotherHoldsTheLock() {
        runRepository.claim(TENANT_ID, RunKind.FULL, Mode.APPLY, "SCHEDULER", "other-pod", Duration.ofHours(2));

        assertThat(runner.runNow(RunKind.DELTA, null, "SCHEDULER")).isEmpty();
        runner.runScheduled(RunKind.DELTA); // logs and returns
        assertThat(source.calls).isZero();
    }

    @Test
    void refreshesOneSchemeByImisId() {
        properties.setMode(Mode.APPLY);

        RunResult result = runner.runNow(RunKind.SCHEME_REFRESH, "8165607", "ADMIN:x").orElseThrow();

        assertThat(result.counts()).containsEntry("schemes.inserted", 1);
        assertThat(source.lastCentreLookup).isEqualTo("8165607");
        assertThat(runRepository.lastAppliedWatermark(TENANT_ID)).isEmpty(); // a refresh never moves the delta
    }

    @Test
    void refusesToRunWhenDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> runner.runNow(RunKind.FULL, null, "SCHEDULER"))
                .isInstanceOf(StateSyncRunner.SyncDisabledException.class);
        assertThat(count("SELECT COUNT(*) FROM common_schema.state_sync_run_table")).isZero();
    }

    @Test
    void refusesToRunWithoutAnActor() {
        properties.setActorUserId(null);

        assertThatThrownBy(() -> runner.runNow(RunKind.FULL, null, "SCHEDULER"))
                .isInstanceOf(StateSyncRunner.SyncMisconfiguredException.class)
                .hasMessageContaining("STATE_SYNC_ACTOR_USER_ID");
    }

    /** In-memory upstream. */
    static class FakeSource implements StateMasterDataSource {
        final List<UpstreamNode> zones = new ArrayList<>();
        final List<UpstreamNode> circles = new ArrayList<>();
        final List<UpstreamNode> divisions = new ArrayList<>();
        final List<UpstreamNode> subdivisions = new ArrayList<>();
        final List<UpstreamNode> districts = new ArrayList<>();
        final List<UpstreamNode> blocks = new ArrayList<>();
        final List<UpstreamNode> panchayats = new ArrayList<>();
        final List<UpstreamNode> villages = new ArrayList<>();
        final List<UpstreamPerson> users = new ArrayList<>();
        final List<UpstreamScheme> schemes = new ArrayList<>();
        boolean failVillages;
        boolean deltaReturnsNothing;
        int calls;
        LocalDateTime lastUpdatedSince;
        String lastCentreLookup;

        @Override public List<UpstreamNode> zones() { calls++; return zones; }
        @Override public List<UpstreamNode> circles() { calls++; return circles; }
        @Override public List<UpstreamNode> divisions() { calls++; return divisions; }
        @Override public List<UpstreamNode> subdivisions() { calls++; return subdivisions; }
        @Override public List<UpstreamNode> districts() { calls++; return districts; }
        @Override public List<UpstreamNode> blocks() { calls++; return blocks; }
        @Override public List<UpstreamNode> panchayats() { calls++; return panchayats; }

        @Override
        public List<UpstreamNode> villages() {
            calls++;
            if (failVillages) {
                throw new StateMasterDataException("village-master: upstream down");
            }
            return villages;
        }

        @Override public List<UpstreamPerson> users() { calls++; return users; }

        @Override
        public List<UpstreamScheme> schemes(LocalDateTime updatedSince) {
            calls++;
            lastUpdatedSince = updatedSince;
            return deltaReturnsNothing && updatedSince == null ? List.of() : schemes;
        }

        @Override
        public Optional<UpstreamScheme> schemeByCode(String code) {
            calls++;
            return schemes.stream().filter(s -> s.code().equals(code)).findFirst();
        }

        @Override
        public Optional<UpstreamScheme> schemeByCentreSchemeId(String centreSchemeId) {
            calls++;
            lastCentreLookup = centreSchemeId;
            return schemes.stream().filter(s -> s.centreSchemeId().equals(centreSchemeId)).findFirst();
        }

        @Override public List<String> archivedSchemeCodes() { calls++; return List.of(); }
        @Override public List<String> blockedUserCodes() { calls++; return List.of(); }
    }
}
