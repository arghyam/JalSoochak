package org.arghyam.jalsoochak.user.repository;

import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ReportsRepository Integration Tests")
class ReportsRepositoryIntegrationTest {

    private static final String SCHEMA = "tenant_mp";

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void dbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ReportsRepository repo;
    @Autowired JdbcTemplate jdbc;
    @Autowired PiiEncryptionService pii;

    private long userId;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM tenant_mp.reports_table");
        jdbc.execute("DELETE FROM tenant_mp.user_table");
        // user_table.id is SERIAL (int4) — read as Integer, then widen.
        Integer id = jdbc.queryForObject("""
                INSERT INTO tenant_mp.user_table
                    (tenant_id, title, email, phone_number, phone_number_hash, user_type,
                     status, email_verification_status, phone_verification_status, created_at, updated_at)
                VALUES (1, ?, 'admin@test.com', ?, ?, 2, 1, true, true, NOW(), NOW())
                RETURNING id
                """, Integer.class, pii.encrypt("Admin"), pii.encrypt("919999999999"), pii.hmac("919999999999"));
        userId = id.longValue();
    }

    @Test
    @DisplayName("insertIfAbsent persists and findByCacheKey returns the row")
    void insertAndFind() {
        UUID id = UUID.randomUUID();
        ReportsRepository.ReportRecord rec = new ReportsRepository.ReportRecord(
                id, "TENANT_STAFF", "CSV", "h".repeat(64), 1L,
                "staff-reports", "staff/mp/2026/05/" + id + ".csv",
                42, 1024L, userId, null);

        boolean inserted = repo.insertIfAbsent(SCHEMA, rec, "{\"name\":null,\"roles\":[],\"status\":null}");
        assertThat(inserted).isTrue();

        Optional<ReportsRepository.ReportRecord> hit = repo.findByCacheKey(
                SCHEMA, "TENANT_STAFF", "CSV", "h".repeat(64), 1L);
        assertThat(hit).isPresent();
        assertThat(hit.get().id()).isEqualTo(id);
        assertThat(hit.get().rowCount()).isEqualTo(42);
        assertThat(hit.get().fileSizeBytes()).isEqualTo(1024L);
        assertThat(hit.get().generatedBy()).isEqualTo(userId);
    }

    @Test
    @DisplayName("insertIfAbsent returns false on duplicate cache key (concurrent winner)")
    void duplicateCacheKeyReturnsFalse() {
        String hash = "a".repeat(64);
        ReportsRepository.ReportRecord first = new ReportsRepository.ReportRecord(
                UUID.randomUUID(), "TENANT_STAFF", "CSV", hash, 1L,
                "staff-reports", "k1.csv", 1, 100L, userId, null);
        assertThat(repo.insertIfAbsent(SCHEMA, first, "{}")).isTrue();

        ReportsRepository.ReportRecord second = new ReportsRepository.ReportRecord(
                UUID.randomUUID(), "TENANT_STAFF", "CSV", hash, 1L,
                "staff-reports", "k2.csv", 1, 100L, userId, null);
        assertThat(repo.insertIfAbsent(SCHEMA, second, "{}")).isFalse();

        Optional<ReportsRepository.ReportRecord> winning = repo.findByCacheKey(
                SCHEMA, "TENANT_STAFF", "CSV", hash, 1L);
        assertThat(winning).isPresent();
        assertThat(winning.get().objectKey()).isEqualTo("k1.csv");
    }

    @Test
    @DisplayName("findByCacheKey is empty when nothing matches")
    void cacheMiss() {
        assertThat(repo.findByCacheKey(SCHEMA, "TENANT_STAFF", "CSV", "z".repeat(64), 99L))
                .isEmpty();
    }
}
