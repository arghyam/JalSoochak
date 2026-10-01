package org.arghyam.jalsoochak.scheme.statesync;

import org.arghyam.jalsoochak.scheme.service.PiiEncryptionService;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamPerson;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamScheme;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Real PostgreSQL with the tables the state sync writes, in their production shape
 * ({@code sql/state-sync-schema.sql} is pg_dumped from a database migrated through V61). Each test
 * starts from an Assam tenant with both location trees configured, the four user types, a state LGD
 * node and a state department node, and one actor user.
 */
@Testcontainers
public abstract class StateSyncIntegrationTestBase {

    protected static final String SCHEMA = "tenant_as";
    protected static final int TENANT_ID = 1;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("sql/state-sync-schema.sql");

    protected static JdbcTemplate jdbc;
    protected static DriverManagerDataSource dataSource;
    protected static PiiEncryptionService pii;

    protected int actor;
    protected int stateLgd;
    protected int stateDept;
    protected StateSyncProperties properties;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        pii = new PiiEncryptionService(randomKey(), randomKey());
    }

    @BeforeEach
    void resetTenant() {
        jdbc.execute("""
                TRUNCATE common_schema.state_sync_issue_table, common_schema.state_sync_run_table,
                         tenant_as.flow_reading_table, tenant_as.user_scheme_mapping_table,
                         tenant_as.scheme_lgd_mapping_table, tenant_as.scheme_department_mapping_table,
                         tenant_as.scheme_master_table, tenant_as.lgd_location_master_table,
                         tenant_as.department_location_master_table, tenant_as.location_config_master_table,
                         tenant_as.user_table, common_schema.user_type_master_table,
                         common_schema.tenant_master_table
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("INSERT INTO common_schema.tenant_master_table (id, state_code, lgd_code, title, status) "
                + "VALUES (?, 'AS', 18, 'Assam', 3)", TENANT_ID);
        for (String type : List.of("STATE_ADMIN", "PUMP_OPERATOR", "SECTION_OFFICER", "SUB_DIVISIONAL_OFFICER",
                "EXECUTIVE_ENGINEER")) {
            jdbc.update("INSERT INTO common_schema.user_type_master_table (c_name) VALUES (?)", type);
        }
        for (int region = 1; region <= 2; region++) {
            for (int level = 1; level <= 5; level++) {
                jdbc.update("INSERT INTO tenant_as.location_config_master_table (region_type, level, level_name) "
                        + "VALUES (?, ?, '{}'::jsonb)", region, level);
            }
        }
        actor = jdbc.queryForObject("INSERT INTO tenant_as.user_table (tenant_id, title, user_type, phone_number, "
                + "status) VALUES (1, 'sync actor', (SELECT id FROM common_schema.user_type_master_table "
                + "WHERE c_name = 'STATE_ADMIN'), 'x', 1) RETURNING id", Integer.class);
        stateLgd = lgd("Assam", 1, null, null);
        stateDept = dept("Assam PHED", 1, null, null);

        properties = new StateSyncProperties();
        properties.setEnabled(true);
        properties.setTenantCode("as");
        properties.setActorUserId(actor);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    protected int lgd(String title, int level, Integer parentId, String code) {
        return jdbc.queryForObject("INSERT INTO tenant_as.lgd_location_master_table (title, lgd_code, "
                        + "lgd_location_config_id, parent_id, status, state_lgd_id) VALUES (?, ?, "
                        + "(SELECT id FROM tenant_as.location_config_master_table WHERE region_type = 1 AND level = ?), "
                        + "?, 1, ?) RETURNING id",
                Integer.class, title, "LGD-" + title, level, parentId, code);
    }

    protected int dept(String title, int level, Integer parentId, String code) {
        return jdbc.queryForObject("INSERT INTO tenant_as.department_location_master_table (title, "
                        + "department_location_config_id, parent_id, status, state_dept_id) VALUES (?, "
                        + "(SELECT id FROM tenant_as.location_config_master_table WHERE region_type = 2 AND level = ?), "
                        + "?, 1, ?) RETURNING id",
                Integer.class, title, level, parentId, code);
    }

    protected int user(String name, String phone91, String type, String stateUserId) {
        return jdbc.queryForObject("INSERT INTO tenant_as.user_table (tenant_id, title, title_hash, user_type, "
                        + "phone_number, phone_number_hash, status, state_user_id) VALUES (1, ?, ?, "
                        + "(SELECT id FROM common_schema.user_type_master_table WHERE c_name = ?), ?, ?, 1, ?) RETURNING id",
                Integer.class, pii.encrypt(name), pii.titleHash(name), type, pii.encrypt(phone91), pii.hmac(phone91),
                stateUserId);
    }

    protected int scheme(String stateId, String centreId, String name, String code) {
        return jdbc.queryForObject("INSERT INTO tenant_as.scheme_master_table (state_scheme_id, centre_scheme_id, "
                        + "scheme_name, work_status, operating_status, state_scheme_code) VALUES (?, ?, ?, 1, 1, ?) "
                        + "RETURNING id",
                Integer.class, stateId, centreId, name, code);
    }

    protected void reading(int schemeId, LocalDateTime at) {
        jdbc.update("INSERT INTO tenant_as.flow_reading_table (scheme_id, observation_time, reading_date, extracted_reading, "
                + "confirmed_reading, correlation_id, created_by, updated_by) VALUES (?, ?, ?, 1, 1, 'c', ?, ?)",
                schemeId, at, at.toLocalDate(), actor, actor);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected static UpstreamPerson person(String code, String name, String phone10, String role) {
        return new UpstreamPerson(code, name, phone10, role);
    }

    protected static UpstreamScheme upstream(String code, String centre, String state, String name,
                                             List<String> subdivisions, List<String> villages,
                                             List<UpstreamPerson> officers) {
        return new UpstreamScheme(code, centre, state, name, "ongoing", "operative", 100, 80, "26.1", "91.7",
                subdivisions, villages, officers, LocalDateTime.of(2026, 9, 1, 10, 0));
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
