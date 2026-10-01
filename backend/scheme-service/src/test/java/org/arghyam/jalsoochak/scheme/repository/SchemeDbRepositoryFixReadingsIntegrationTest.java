package org.arghyam.jalsoochak.scheme.repository;

import org.arghyam.jalsoochak.scheme.dto.SchemeYesterdayFinalReadingDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchemeDbRepository#listSchemesWithYesterdayFinalReadingForUser} against a real PostgreSQL
 * instance: the SO/SDO fix-readings list shows each scheme's latest BFM reading only.
 */
@Testcontainers
class SchemeDbRepositoryFixReadingsIntegrationTest {

    private static final String SCHEMA = "tenant_mp";
    private static final int OFFICER = 7;
    private static final int OPERATOR = 9;
    private static final LocalDate DAY = LocalDate.of(2026, 3, 1);
    /** common_schema.channel_master_table ids. */
    private static final int BFM = 1;
    private static final int ELM = 2;
    private static final int PDU = 3;

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static JdbcTemplate jdbcTemplate;

    private SchemeDbRepository repository;
    private int schemeId;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void mapOfficerToOneScheme() {
        jdbcTemplate.execute("DELETE FROM tenant_mp.flow_reading_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.user_scheme_mapping_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.scheme_master_table");
        repository = new SchemeDbRepository(jdbcTemplate);

        schemeId = jdbcTemplate.queryForObject(
                "INSERT INTO tenant_mp.scheme_master_table (state_scheme_id, scheme_name) "
                        + "VALUES ('SS-1', 'Fix Readings Scheme') RETURNING id",
                Integer.class);
        jdbcTemplate.update(
                "INSERT INTO tenant_mp.user_scheme_mapping_table (user_id, scheme_id, status) VALUES (?, ?, 1)",
                OFFICER, schemeId);
    }

    private void insertReading(String reading, LocalDateTime readingAt, Integer channel) {
        jdbcTemplate.update("INSERT INTO tenant_mp.flow_reading_table "
                        + "(scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'corr-1', ?, ?)",
                schemeId, readingAt, readingAt.toLocalDate(),
                new BigDecimal(reading), new BigDecimal(reading), channel, OPERATOR);
    }

    private BigDecimal yesterdayFinalReading() {
        List<SchemeYesterdayFinalReadingDTO> rows =
                repository.listSchemesWithYesterdayFinalReadingForUser(SCHEMA, OFFICER, null, 0, 10);
        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.getSchemeId()).isEqualTo(schemeId));
        return rows.get(0).getYesterdayFinalReading();
    }

    @Test
    void showsTheLatestBfmReading() {
        insertReading("100", DAY.atTime(6, 0), BFM);
        insertReading("101.5", DAY.plusDays(1).atTime(6, 0), BFM);

        assertThat(yesterdayFinalReading()).isEqualByComparingTo("101.5");
    }

    @Test
    void readsALegacyNullChannelRowAsBfm() {
        insertReading("100", DAY.atTime(6, 0), null);

        assertThat(yesterdayFinalReading()).isEqualByComparingTo("100");
    }

    @Test
    void skipsALaterElmReading() {
        insertReading("100", DAY.atTime(6, 0), BFM);
        insertReading("5000", DAY.atTime(8, 0), ELM);

        assertThat(yesterdayFinalReading()).isEqualByComparingTo("100");
    }

    @ParameterizedTest(name = "channel_id {0}")
    @ValueSource(ints = {ELM, PDU})
    void showsANonBfmOnlySchemeLikeOneWithNoReadings(int channel) {
        insertReading("40", DAY.atTime(6, 0), channel);

        assertThat(yesterdayFinalReading()).isEqualByComparingTo("0");
    }

    @Test
    void showsZeroForASchemeWithNoReadings() {
        assertThat(yesterdayFinalReading()).isEqualByComparingTo("0");
    }
}
