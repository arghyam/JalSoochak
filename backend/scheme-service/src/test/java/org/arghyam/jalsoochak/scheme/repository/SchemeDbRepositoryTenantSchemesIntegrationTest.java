package org.arghyam.jalsoochak.scheme.repository;

import org.arghyam.jalsoochak.scheme.statesync.StateSyncIntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The two lookups the dimension republish backfill relies on, against real PostgreSQL. */
class SchemeDbRepositoryTenantSchemesIntegrationTest extends StateSyncIntegrationTestBase {

    @Test
    void findsTheTenantByStateCodeIgnoringCase() {
        SchemeDbRepository repository = new SchemeDbRepository(jdbc);

        assertThat(repository.findTenantIdByStateCode("as")).contains(TENANT_ID);
        assertThat(repository.findTenantIdByStateCode(" AS ")).contains(TENANT_ID);
        assertThat(repository.findTenantIdByStateCode("up")).isEmpty();
        assertThat(repository.findTenantIdByStateCode(" ")).isEmpty();
    }

    @Test
    void listsOnlyLiveSchemesInIdOrder() {
        SchemeDbRepository repository = new SchemeDbRepository(jdbc);
        int first = scheme("1", "11", "A", null);
        int deleted = scheme("2", "22", "B", null);
        int third = scheme("3", "33", "C", null);
        jdbc.update("UPDATE tenant_as.scheme_master_table SET deleted_at = NOW() WHERE id = ?", deleted);

        assertThat(repository.findLiveSchemeIds("tenant_as")).containsExactly(first, third);
    }
}
