package org.arghyam.jalsoochak.scheme.statesync.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.scheme.statesync.StateSyncIntegrationTestBase;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class StateSyncRunRepositoryIntegrationTest extends StateSyncIntegrationTestBase {

    private StateSyncRunRepository repository;

    @BeforeEach
    void setUp() {
        repository = new StateSyncRunRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void resolvesTheTenantByStateCodeCaseInsensitively() {
        assertThat(repository.findTenant("as")).contains(new StateSyncRunRepository.Tenant(TENANT_ID, "AS", "tenant_as"));
        assertThat(repository.findTenant("xx")).isEmpty();
    }

    @Test
    void onlyOneOfManyConcurrentClaimsWins() throws Exception {
        int pods = 8;
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<Long>>> results = new ArrayList<>();
        for (int i = 0; i < pods; i++) {
            String owner = "pod-" + i;
            Callable<Optional<Long>> claim = () -> {
                start.await();
                return repository.claim(TENANT_ID, RunKind.FULL, Mode.APPLY, "SCHEDULER", owner, Duration.ofHours(2));
            };
            results.add(pool.submit(claim));
        }
        start.countDown();
        int winners = 0;
        for (Future<Optional<Long>> result : results) {
            winners += result.get().isPresent() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM common_schema.state_sync_run_table WHERE status = 'RUNNING'")).isEqualTo(1);
    }

    @Test
    void aDeltaCannotStartWhileTheFullRunHoldsTheLock() {
        assertThat(repository.claim(TENANT_ID, RunKind.FULL, Mode.APPLY, "SCHEDULER", "a", Duration.ofHours(2))).isPresent();
        assertThat(repository.claim(TENANT_ID, RunKind.DELTA, Mode.APPLY, "SCHEDULER", "b", Duration.ofHours(2))).isEmpty();
    }

    @Test
    void theLockIsFreeAgainOnceTheRunFinishes() {
        long first = repository.claim(TENANT_ID, RunKind.FULL, Mode.APPLY, "SCHEDULER", "a", Duration.ofHours(2)).orElseThrow();
        repository.finish(first, true, new SyncReport(), null);

        assertThat(repository.claim(TENANT_ID, RunKind.DELTA, Mode.APPLY, "SCHEDULER", "a", Duration.ofHours(2))).isPresent();
    }

    @Test
    void aCrashedPodsStaleClaimIsTakenOver() {
        long crashed = repository.claim(TENANT_ID, RunKind.FULL, Mode.APPLY, "SCHEDULER", "dead-pod", Duration.ofHours(2)).orElseThrow();
        jdbc.update("UPDATE common_schema.state_sync_run_table SET heartbeat_at = NOW() - INTERVAL '3 hours' WHERE id = ?", crashed);

        Optional<Long> next = repository.claim(TENANT_ID, RunKind.FULL, Mode.APPLY, "SCHEDULER", "live-pod", Duration.ofHours(2));

        assertThat(next).isPresent();
        assertThat(jdbc.queryForObject("SELECT status FROM common_schema.state_sync_run_table WHERE id = ?", String.class, crashed))
                .isEqualTo("ABANDONED");
        // The dead pod finishing late does not overwrite the takeover.
        repository.finish(crashed, true, new SyncReport(), null);
        assertThat(jdbc.queryForObject("SELECT status FROM common_schema.state_sync_run_table WHERE id = ?", String.class, crashed))
                .isEqualTo("ABANDONED");
    }

    @Test
    void aFreshClaimIsNotTakenOver() {
        repository.claim(TENANT_ID, RunKind.FULL, Mode.APPLY, "SCHEDULER", "busy-pod", Duration.ofHours(2)).orElseThrow();
        jdbc.update("UPDATE common_schema.state_sync_run_table SET heartbeat_at = NOW() - INTERVAL '30 minutes'");

        assertThat(repository.claim(TENANT_ID, RunKind.FULL, Mode.APPLY, "SCHEDULER", "other", Duration.ofHours(2))).isEmpty();
    }

    @Test
    void theDeltaWatermarkComesOnlyFromSucceededApplyRuns() {
        finishRun(Mode.APPLY, RunKind.FULL, true, LocalDateTime.of(2026, 9, 1, 10, 0));
        finishRun(Mode.DRY_RUN, RunKind.DELTA, true, LocalDateTime.of(2026, 9, 5, 10, 0));
        finishRun(Mode.APPLY, RunKind.DELTA, false, LocalDateTime.of(2026, 9, 6, 10, 0));
        finishRun(Mode.APPLY, RunKind.SCHEME_REFRESH, true, LocalDateTime.of(2026, 9, 7, 10, 0));
        finishRun(Mode.APPLY, RunKind.DELTA, true, null);

        assertThat(repository.lastAppliedWatermark(TENANT_ID)).contains(LocalDateTime.of(2026, 9, 1, 10, 0));
    }

    @Test
    void persistsAndListsIssuesAndRuns() {
        long runId = repository.claim(TENANT_ID, RunKind.FULL, Mode.DRY_RUN, "ADMIN:abc", "pod", Duration.ofHours(2)).orElseThrow();
        SyncReport report = new SyncReport();
        report.issue("SCHEME", "SCH-1", "CONFLICT_IDS_POINT_TO_DIFFERENT_SCHEMES", Map.of("centreMatch", 4));
        report.count("schemes.updated");
        repository.insertIssues(runId, TENANT_ID, report.issues());
        repository.finish(runId, true, report, null);

        assertThat(repository.listIssues(TENANT_ID, runId, "CONFLICT_IDS_POINT_TO_DIFFERENT_SCHEMES", 10, 0))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.upstreamCode()).isEqualTo("SCH-1");
                    assertThat(i.detail()).contains("\"centreMatch\": 4");
                });
        assertThat(repository.listRuns(TENANT_ID, 5)).singleElement()
                .satisfies(r -> {
                    assertThat(r.status()).isEqualTo("SUCCEEDED");
                    assertThat(r.mode()).isEqualTo("DRY_RUN");
                    assertThat(r.counts()).contains("\"schemes.updated\": 1");
                });
    }

    private void finishRun(Mode mode, RunKind kind, boolean ok, LocalDateTime watermark) {
        long id = repository.claim(TENANT_ID, kind, mode, "SCHEDULER", "pod", Duration.ofHours(2)).orElseThrow();
        SyncReport report = new SyncReport();
        report.seenSourceUpdatedAt(watermark);
        repository.finish(id, ok, report, ok ? null : "boom");
    }
}
