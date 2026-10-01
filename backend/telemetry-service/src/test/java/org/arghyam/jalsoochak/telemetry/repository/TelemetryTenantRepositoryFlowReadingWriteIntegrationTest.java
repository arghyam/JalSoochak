package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reading writes against a real PostgreSQL instance: the channel and unit land with the row, and
 * the version each write returns is the {@code updated_at} the database stored.
 */
@Testcontainers
class TelemetryTenantRepositoryFlowReadingWriteIntegrationTest {

    private static final String MIGRATED_SCHEMA = "tenant_as";
    private static final String PRE_V56_SCHEMA = "tenant_zz";
    private static final long SCHEME = 1L;
    private static final long OPERATOR = 9L;
    private static final LocalDateTime READING_AT = LocalDate.of(2026, 3, 1).atTime(6, 30);

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    private static TelemetryTenantRepository repository() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        TelemetryTenantRepository repository =
                new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        repository.invalidateMetadataCaches();
        return repository;
    }

    private static FlowReadingVersion insert(String schema, ReadingChannel channel, String submittedUnit) {
        return repository().createFlowReading(schema, SCHEME, OPERATOR, READING_AT,
                BigDecimal.ZERO, new BigDecimal("90"), "corr-" + System.nanoTime(), null, null, null,
                channel, submittedUnit);
    }

    private static Map<String, Object> stored(String schema, long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM " + schema + ".flow_reading_table WHERE id = ?", id);
    }

    private static LocalDateTime storedVersion(String schema, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM " + schema + ".flow_reading_table WHERE id = ?", LocalDateTime.class, id);
    }

    @Test
    void insertStoresTheChannelAndUnitAndReturnsTheStoredVersion() {
        FlowReadingVersion version = insert(MIGRATED_SCHEMA, ReadingChannel.PDU, "min");

        Map<String, Object> row = stored(MIGRATED_SCHEMA, version.id());
        assertEquals(ReadingChannel.PDU.getCode(), row.get("channel_id"));
        assertEquals("min", row.get("submitted_unit"));
        assertNotNull(version.updatedAt());
        assertEquals(storedVersion(MIGRATED_SCHEMA, version.id()), version.updatedAt());
        assertEquals(version.updatedAt(),
                repository().findFlowReadingById(MIGRATED_SCHEMA, version.id()).orElseThrow().updatedAt());
    }

    /** A schema that predates V56 still stores the reading and its channel; only the unit is dropped. */
    @Test
    void insertDropsOnlyTheUnitOnAPreV56Schema() {
        FlowReadingVersion version = insert(PRE_V56_SCHEMA, ReadingChannel.ELM, "kW.h");

        Map<String, Object> row = stored(PRE_V56_SCHEMA, version.id());
        assertEquals(ReadingChannel.ELM.getCode(), row.get("channel_id"));
        assertFalse(row.containsKey("submitted_unit"));
        assertEquals(storedVersion(PRE_V56_SCHEMA, version.id()), version.updatedAt());
    }

    @Test
    void placeholderUpdateWritesTheChannelAndUnitAndKeepsThemWhenGivenNone() {
        long id = insert(MIGRATED_SCHEMA, null, null).id();

        FlowReadingVersion version = repository().updateFlowReadingFromIngestion(MIGRATED_SCHEMA, id, READING_AT,
                BigDecimal.ZERO, new BigDecimal("120"), "corr-1", null, "", null, OPERATOR, ReadingChannel.BFM, "m3");

        assertEquals(new FlowReadingVersion(id, storedVersion(MIGRATED_SCHEMA, id)), version);
        // The legacy overload passes no channel or unit, which leaves both as they are.
        repository().updateFlowReadingFromIngestion(MIGRATED_SCHEMA, id, READING_AT,
                BigDecimal.ZERO, new BigDecimal("121"), "corr-1", "", null, OPERATOR);

        Map<String, Object> row = stored(MIGRATED_SCHEMA, id);
        assertEquals(ReadingChannel.BFM.getCode(), row.get("channel_id"));
        assertEquals("m3", row.get("submitted_unit"));
    }

    @Test
    void correctionWritesTheUnitItArrivedInAndDropsItOnAPreV56Schema() {
        long migrated = insert(MIGRATED_SCHEMA, ReadingChannel.PDU, "min").id();
        long preV56 = insert(PRE_V56_SCHEMA, ReadingChannel.PDU, null).id();

        repository().updateConfirmedReading(MIGRATED_SCHEMA, migrated, new BigDecimal("120"), OPERATOR, 1, "h");
        repository().updateConfirmedReading(PRE_V56_SCHEMA, preV56, new BigDecimal("120"), OPERATOR, 1, "h");

        Map<String, Object> row = stored(MIGRATED_SCHEMA, migrated);
        assertEquals(0, new BigDecimal("120").compareTo((BigDecimal) row.get("confirmed_reading")));
        assertEquals("h", row.get("submitted_unit"));
        assertEquals(0, new BigDecimal("120").compareTo(
                (BigDecimal) stored(PRE_V56_SCHEMA, preV56).get("confirmed_reading")));
    }

    @Test
    void placeholderUpdateOfAMissingRowReturnsNoVersion() {
        FlowReadingVersion version = repository().updateFlowReadingFromIngestion(MIGRATED_SCHEMA, 404_404L,
                READING_AT, BigDecimal.ZERO, BigDecimal.ONE, "corr-1", null, "", null, OPERATOR, ReadingChannel.BFM, "m3");

        assertNull(version.updatedAt());
    }

    /**
     * {@code updated_at} is the submission's version, and analytics keeps the newest. With
     * {@code NOW()} every write in one transaction stored the transaction's start time, so two
     * versions of a row could tie, and a transaction that started first but committed last would
     * store the older-looking version. {@code clock_timestamp()} moves on with every statement.
     */
    @Test
    void everyWriteInOneTransactionMovesTheVersionOn() {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        LocalDateTime[] versions = transaction.execute(status -> {
            TelemetryTenantRepository repository = repository();
            FlowReadingVersion inserted = insert(MIGRATED_SCHEMA, ReadingChannel.BFM, "m3");
            FlowReadingVersion updated = repository.updateFlowReadingFromIngestion(MIGRATED_SCHEMA,
                    inserted.id(), READING_AT, BigDecimal.ZERO, new BigDecimal("95"), "corr-1", null, "",
                    null, OPERATOR, ReadingChannel.BFM, "m3");
            repository.updateConfirmedReading(MIGRATED_SCHEMA, inserted.id(), new BigDecimal("96"), OPERATOR);
            LocalDateTime corrected = repository.findFlowReadingById(MIGRATED_SCHEMA, inserted.id())
                    .orElseThrow().updatedAt();
            return new LocalDateTime[]{inserted.updatedAt(), updated.updatedAt(), corrected};
        });

        assertNotNull(versions);
        assertTrue(versions[1].isAfter(versions[0]), "placeholder update after insert");
        assertTrue(versions[2].isAfter(versions[1]), "correction after placeholder update");
    }
}
