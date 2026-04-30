package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.response.EscalationListItemDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@Import(EscalationQueryService.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EscalationQueryServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_escalation_query_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.schemas", () -> "analytics_schema");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EscalationQueryService escalationQueryService;

    private LocalDateTime tEarly;
    private LocalDateTime tMid;
    private LocalDateTime tLate;
    private LocalDate rangeStart;
    private LocalDate rangeEnd;

    @BeforeEach
    void setUp() {
        LocalDate today = LocalDate.now();
        rangeEnd = today;
        rangeStart = today.minusDays(20);
        tEarly = today.minusDays(10).atTime(12, 0);
        tMid = today.minusDays(9).atTime(12, 0);
        tLate = today.minusDays(8).atTime(12, 0);

        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.fact_escalation_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_department_location_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_user_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (1, 'mp', 'MP', 'IN', 1, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, created_at, updated_at, title)
                VALUES (11, 1, 'u11@test.local', 1, NOW(), NOW(), 'U11')
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES
                (100, 1, 'L100', 'Parent', 'Parent LGD', 1, 100, NULL, NULL, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (101, 1, 'L101', 'ChildA', 'Child A', 2, 100, 101, NULL, NULL, NULL, NULL, NULL, NOW(), NOW()),
                (102, 1, 'L102', 'ChildB', 'Child B', 2, 100, 102, NULL, NULL, NULL, NULL, NULL, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at, geom)
                VALUES
                (200, 1, 'Parent Dept', 'Parent Dept', 1, 200, NULL, NULL, NULL, NULL, NULL, NOW(), NOW(), NULL),
                (201, 1, 'Child Dept A', 'Child Dept A', 2, 200, 201, NULL, NULL, NULL, NULL, NOW(), NOW(), NULL),
                (202, 1, 'Child Dept B', 'Child Dept B', 2, 200, 202, NULL, NULL, NULL, NULL, NOW(), NOW(), NULL)
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'Alpha Village Scheme', 1001, 2001, 0.0, 0.0, 100, 100, 101, NULL, NULL, NULL, NULL, 200, 200, 201, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'Beta Town Supply', 1002, 2002, 0.0, 0.0, 100, 100, 102, NULL, NULL, NULL, NULL, 200, 200, 202, NULL, NULL, NULL, NULL, 1, 20, 20, 20, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_escalation_table
                (tenant_id, scheme_id, escalation_type, message, correlation_id, user_id, resolution_status, remark, created_at, updated_at)
                VALUES
                (1, 1, 'NUDGE', 'm1', 'c1', 11, 0, 'r1', ?, ?),
                (1, 2, 'ESCALATION', 'm2', 'c2', 11, 1, 'r2', ?, ?),
                (1, NULL, 'NUDGE', 'officer', 'c3', 11, 0, NULL, ?, ?)
                """, tEarly, tEarly, tMid, tMid, tLate, tLate);
    }

    @Test
    void countEscalations_usesDefaultDateWindowAndOptionalFilters() {
        long allForTenant = escalationQueryService.countEscalations(1, null, null, null);
        assertThat(allForTenant).isEqualTo(3);

        long forUser = escalationQueryService.countEscalations(1, 11, rangeStart, rangeEnd);
        assertThat(forUser).isEqualTo(3);

        long none = escalationQueryService.countEscalations(99, null, null, null);
        assertThat(none).isZero();
    }

    @Test
    void getEscalations_withoutSchemeName_enrichesSchemeNamesAndSupportsPagination() {
        Page<EscalationListItemDto> page = escalationQueryService.getEscalations(
                1,
                11,
                null,
                null,
                null,
                null,
                rangeStart,
                rangeEnd,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "createdAt"))
        );

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().getFirst().getCreatedAt()).isEqualTo(tEarly);
        assertThat(page.getContent().get(1).getCreatedAt()).isEqualTo(tMid);

        Page<EscalationListItemDto> full = escalationQueryService.getEscalations(
                1, 11, null, null, null, null, rangeStart, rangeEnd,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "createdAt"))
        );
        EscalationListItemDto withScheme = full.getContent().stream()
                .filter(d -> d.getSchemeId() != null && d.getSchemeId() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(withScheme.getSchemeName()).isEqualTo("Alpha Village Scheme");

        EscalationListItemDto officer = full.getContent().stream()
                .filter(d -> d.getSchemeId() == null)
                .findFirst()
                .orElseThrow();
        assertThat(officer.getSchemeName()).isNull();
    }

    @Test
    void getEscalations_withoutSchemeJoin_defaultSortIsCreatedAtDescWhenSortUnspecified() {
        Page<EscalationListItemDto> page = escalationQueryService.getEscalations(
                1, 11, null, null, null, null, rangeStart, rangeEnd,
                PageRequest.of(0, 10)
        );
        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent().getFirst().getCreatedAt()).isEqualTo(tLate);
    }

    @Test
    void getEscalations_withSchemeNameFilter_usesJoinQuery() {
        Page<EscalationListItemDto> page = escalationQueryService.getEscalations(
                1,
                11,
                null,
                null,
                "  Village  ",
                null,
                rangeStart,
                rangeEnd,
                PageRequest.of(0, 10)
        );
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getSchemeName()).isEqualTo("Alpha Village Scheme");
        assertThat(page.getContent().getFirst().getEscalationType()).isEqualTo("NUDGE");
    }

    @Test
    void getEscalations_filtersByEscalationTypeSchemeIdAndResolutionStatus() {
        Page<EscalationListItemDto> page = escalationQueryService.getEscalations(
                1,
                11,
                "ESCALATION",
                2,
                null,
                1,
                rangeStart,
                rangeEnd,
                PageRequest.of(0, 10)
        );
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getMessage()).isEqualTo("m2");
    }

    @Test
    void getEscalations_blankSchemeNameUsesPathWithoutJoin() {
        Page<EscalationListItemDto> page = escalationQueryService.getEscalations(
                1, 11, null, null, "   ", null, rangeStart, rangeEnd,
                PageRequest.of(0, 10)
        );
        assertThat(page.getTotalElements()).isEqualTo(3);
    }
}
