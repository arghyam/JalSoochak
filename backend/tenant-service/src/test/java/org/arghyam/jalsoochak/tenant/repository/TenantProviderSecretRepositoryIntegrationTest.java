package org.arghyam.jalsoochak.tenant.repository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.arghyam.jalsoochak.tenant.dto.internal.TenantProviderSecretDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantSecretKeyDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.service.PiiEncryptionService;
import org.arghyam.jalsoochak.tenant.service.TenantSchedulerManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MESSAGING-PROVIDER-SECRETS: integration tests for {@link TenantProviderSecretRepository}
 * against real PostgreSQL via Testcontainers.
 *
 * <p>The schema under test is the <b>real V44 migration</b>, read from
 * {@code classpath:db/migration/} (tenant-service's pom copies {@code ../database/V*.sql}
 * there), applied to a database that already holds tenants and admin users — the state a
 * production database is in when the migration runs. So this class covers the migration and
 * the repository at once, and a change to the DDL that breaks either shows up here.
 *
 * <p>The constraints are half the design: the composite FK and the two unique indexes are
 * what make an orphaned key version or a second live secret unstorable, so they are asserted
 * directly rather than assumed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DisplayName("TenantProviderSecretRepository Integration Tests")
class TenantProviderSecretRepositoryIntegrationTest {

    private static final int TENANT_MP = 101;
    private static final int TENANT_TR = 102;
    private static final int ADMIN_USER = 900;
    private static final String MASTER_V1 = "v1";

    /** Stand-in ciphertext. The repository never decrypts, so any base64-ish text will do. */
    private static final String CIPHERTEXT = "Zm9vYmFyYmF6cXV4Y2lwaGVydGV4dA==";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/tenant-common-test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /**
     * Seeds tenants and an admin user, then applies V44 on top — proving the migration lands
     * on a populated database and that its foreign keys accept existing rows.
     */
    @BeforeAll
    static void seedThenMigrate() throws Exception {
        String migration = new String(
                new ClassPathResource("db/migration/V44__create_tenant_provider_secret_tables.sql")
                        .getInputStream().readAllBytes(), UTF_8);

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO common_schema.tenant_master_table (id, state_code, title, status, lgd_code)
                    VALUES (101, 'MP', 'Madhya Pradesh', 3, 23), (102, 'TR', 'Tripura', 3, 16)
                    """);
            statement.execute("INSERT INTO common_schema.tenant_admin_user_master_table (id) VALUES (900)");
            statement.execute(migration);
        }
    }

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockBean
    private TenantSchedulerManager tenantSchedulerManager;

    /** PiiEncryptionService needs key env vars that tests do not provide. */
    @MockBean
    private PiiEncryptionService piiEncryptionService;

    @Autowired
    private TenantProviderSecretRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM common_schema.tenant_provider_secret");
        jdbcTemplate.update("DELETE FROM common_schema.tenant_secret_key");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private TenantSecretKeyDTO givenActiveKey(int tenantId, int keyVersion) {
        return repository.insertActiveKey(tenantId, keyVersion, "wrapped-" + tenantId + "-" + keyVersion,
                MASTER_V1, ADMIN_USER);
    }

    private TenantProviderSecretDTO givenSecret(int tenantId, MessagingChannel channel, String name, int keyVersion) {
        return repository.upsertSecret(tenantId, channel, name, CIPHERTEXT, keyVersion, ADMIN_USER);
    }

    /**
     * Runs a query on a connection of its own. An advisory lock leaves nothing the session holding
     * it can assert on, so every check below has to look at it from outside.
     */
    private static List<Long> onAnotherSession(String sql) {
        try (Connection other = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = other.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            List<Long> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getLong(1));
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    // ── migration ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("V44 migration")
    class Migration {

        @Test
        @DisplayName("Both tables exist in common_schema")
        void tablesCreated() {
            List<String> tables = jdbcTemplate.queryForList("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'common_schema'
                      AND table_name IN ('tenant_secret_key', 'tenant_provider_secret')
                    ORDER BY table_name
                    """, String.class);

            assertThat(tables).containsExactly("tenant_provider_secret", "tenant_secret_key");
        }

        @Test
        @DisplayName("Both partial indexes and both unique constraints exist")
        void indexesCreated() {
            List<String> indexes = jdbcTemplate.queryForList("""
                    SELECT indexname FROM pg_indexes
                    WHERE schemaname = 'common_schema'
                      AND tablename IN ('tenant_secret_key', 'tenant_provider_secret')
                    ORDER BY indexname
                    """, String.class);

            assertThat(indexes).contains(
                    "uq_tenant_secret_key",
                    "uq_tenant_secret_key_active",
                    "uq_tenant_provider_secret",
                    "idx_tenant_provider_secret_tenant_channel");
        }

        @Test
        @DisplayName("Existing tenants survived the migration")
        void existingTenantsIntact() {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.tenant_master_table WHERE id IN (101, 102)",
                    Integer.class)).isEqualTo(2);
        }

        @Test
        @DisplayName("create_tenant_schema() was not touched — both tables are in common_schema")
        void tenantSchemaFunctionUnchanged() {
            // A per-tenant copy of a credential table would defeat the point: message-service
            // resolves a secret without knowing the tenant's schema name.
            Integer functions = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_proc p
                    JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'common_schema' AND p.proname LIKE 'create_tenant_schema_v44%'
                    """, Integer.class);

            assertThat(functions).isZero();
        }
    }

    // ── tenant_secret_key ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Data keys")
    class DataKeys {

        @Test
        @DisplayName("An inserted key is ACTIVE and findable")
        void insertAndFindActive() {
            TenantSecretKeyDTO inserted = givenActiveKey(TENANT_MP, 1);

            assertThat(inserted.getStatus()).isEqualTo(TenantProviderSecretRepository.KEY_STATUS_ACTIVE);
            assertThat(inserted.getMasterKeyId()).isEqualTo(MASTER_V1);
            assertThat(inserted.getCreatedBy()).isEqualTo(ADMIN_USER);

            Optional<TenantSecretKeyDTO> found = repository.findActiveKey(TENANT_MP);
            assertThat(found).isPresent();
            assertThat(found.get().getKeyVersion()).isEqualTo(1);
            assertThat(found.get().getWrappedKey()).isEqualTo("wrapped-101-1");
        }

        @Test
        @DisplayName("A tenant with no key resolves to empty rather than failing")
        void noKeyIsEmpty() {
            assertThat(repository.findActiveKey(TENANT_TR)).isEmpty();
            assertThat(repository.findMaxKeyVersion(TENANT_TR)).isZero();
        }

        @Test
        @DisplayName("A second ACTIVE key for the same tenant is refused by the partial unique index")
        void onlyOneActiveKeyPerTenant() {
            givenActiveKey(TENANT_MP, 1);

            assertThatThrownBy(() -> givenActiveKey(TENANT_MP, 2))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Retiring the old key frees the index for the next ACTIVE one")
        void retireThenInsertNextVersion() {
            TenantSecretKeyDTO first = givenActiveKey(TENANT_MP, 1);

            assertThat(repository.retireKey(first.getId(), ADMIN_USER)).isEqualTo(1);
            TenantSecretKeyDTO second = givenActiveKey(TENANT_MP, 2);

            assertThat(repository.findActiveKey(TENANT_MP)).get()
                    .extracting(TenantSecretKeyDTO::getKeyVersion).isEqualTo(2);
            assertThat(repository.findKey(TENANT_MP, 1)).get()
                    .extracting(TenantSecretKeyDTO::getStatus)
                    .isEqualTo(TenantProviderSecretRepository.KEY_STATUS_RETIRED);
            assertThat(second.getKeyVersion()).isEqualTo(2);
            assertThat(repository.findMaxKeyVersion(TENANT_MP)).isEqualTo(2);
        }

        @Test
        @DisplayName("Retiring an already-retired key is a no-op")
        void retireIsIdempotent() {
            TenantSecretKeyDTO key = givenActiveKey(TENANT_MP, 1);
            repository.retireKey(key.getId(), ADMIN_USER);

            assertThat(repository.retireKey(key.getId(), ADMIN_USER)).isZero();
        }

        @Test
        @DisplayName("The same key version cannot be issued twice to one tenant")
        void keyVersionIsUniquePerTenant() {
            TenantSecretKeyDTO key = givenActiveKey(TENANT_MP, 1);
            repository.retireKey(key.getId(), ADMIN_USER);

            assertThatThrownBy(() -> givenActiveKey(TENANT_MP, 1))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Two tenants may each hold version 1")
        void keyVersionsAreScopedToTheTenant() {
            givenActiveKey(TENANT_MP, 1);
            givenActiveKey(TENANT_TR, 1);

            assertThat(repository.findActiveKey(TENANT_MP)).isPresent();
            assertThat(repository.findActiveKey(TENANT_TR)).isPresent();
        }

        @Test
        @DisplayName("A key for a non-existent tenant is refused by the foreign key")
        void keyRequiresAnExistingTenant() {
            assertThatThrownBy(() -> givenActiveKey(9999, 1))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Re-wrapping replaces the wrapping and the master key id only")
        void updateWrappedKey() {
            TenantSecretKeyDTO key = givenActiveKey(TENANT_MP, 1);

            assertThat(repository.updateWrappedKey(key.getId(), "rewrapped", "v2", ADMIN_USER)).isEqualTo(1);

            TenantSecretKeyDTO reloaded = repository.findKey(TENANT_MP, 1).orElseThrow();
            assertThat(reloaded.getWrappedKey()).isEqualTo("rewrapped");
            assertThat(reloaded.getMasterKeyId()).isEqualTo("v2");
            assertThat(reloaded.getKeyVersion()).isEqualTo(1);
            assertThat(reloaded.getStatus()).isEqualTo(TenantProviderSecretRepository.KEY_STATUS_ACTIVE);
        }

        @Test
        @DisplayName("findAllKeys returns retired versions too, ordered for a stable rewrap")
        void findAllKeysIncludesRetired() {
            TenantSecretKeyDTO first = givenActiveKey(TENANT_MP, 1);
            repository.retireKey(first.getId(), ADMIN_USER);
            givenActiveKey(TENANT_MP, 2);
            givenActiveKey(TENANT_TR, 1);

            assertThat(repository.findAllKeys())
                    .extracting(TenantSecretKeyDTO::getTenantId, TenantSecretKeyDTO::getKeyVersion)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(TENANT_MP, 1),
                            org.assertj.core.groups.Tuple.tuple(TENANT_MP, 2),
                            org.assertj.core.groups.Tuple.tuple(TENANT_TR, 1));
        }

        @Test
        @DisplayName("toString() renders the row's identity and never the wrapped key")
        void toStringOmitsWrappedKey() {
            TenantSecretKeyDTO key = givenActiveKey(TENANT_MP, 1);

            assertThat(key.toString())
                    .contains("tenantId=101", "keyVersion=1", "masterKeyId=v1", "status=ACTIVE")
                    .doesNotContain("wrapped-101-1");
        }
    }

    // ── key lock ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Key lock")
    class KeyLock {

        /**
         * The two-argument {@code pg_advisory_xact_lock(int, int)} is recorded with the first key
         * in {@code classid}, the second in {@code objid} and {@code objsubid = 2}.
         */
        private static final String HELD_TENANT_IDS =
                "SELECT objid::bigint FROM pg_locks WHERE locktype = 'advisory' AND objsubid = 2";

        private static final String HELD_NAMESPACES =
                "SELECT classid::bigint FROM pg_locks WHERE locktype = 'advisory' AND objsubid = 2";

        @Test
        @DisplayName("Locks the tenant it was given, and only until the transaction ends")
        void isKeyedOnTheTenantAndEndsWithTheTransaction() {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                repository.lockKeys(TENANT_MP);

                assertThat(onAnotherSession(HELD_TENANT_IDS)).containsExactly((long) TENANT_MP);
            });

            // Transaction-scoped: nothing has to unlock it, and nothing is left holding it once the
            // surrounding transaction commits or rolls back.
            assertThat(onAnotherSession(HELD_TENANT_IDS)).isEmpty();
        }

        @Test
        @DisplayName("Shuts out a second writer for the same tenant, never for another")
        void excludesTheSameTenantOnly() {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                repository.lockKeys(TENANT_MP);
                int namespace = Math.toIntExact(onAnotherSession(HELD_NAMESPACES).get(0));

                // The second admin writing this tenant's secrets waits, rather than reading the key
                // state the first one is part-way through changing.
                assertThat(tryLockOnAnotherSession(namespace, TENANT_MP)).isFalse();
                // Another tenant's write is untouched: the lock is per tenant, not per table.
                assertThat(tryLockOnAnotherSession(namespace, TENANT_TR)).isTrue();
            });
        }

        private boolean tryLockOnAnotherSession(int namespace, int tenantId) {
            try (Connection other = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    PreparedStatement ps = other.prepareStatement(
                            "SELECT pg_try_advisory_xact_lock(?, ?)")) {
                ps.setInt(1, namespace);
                ps.setInt(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ── tenant_provider_secret ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Secrets")
    class Secrets {

        @BeforeEach
        void givenKeys() {
            givenActiveKey(TENANT_MP, 1);
            givenActiveKey(TENANT_TR, 1);
        }

        @Test
        @DisplayName("A new secret is inserted with a generated uuid")
        void insertSecret() {
            TenantProviderSecretDTO secret = givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);

            assertThat(secret.getUuid()).isNotBlank().hasSize(36);
            assertThat(secret.getChannel()).isEqualTo(MessagingChannel.SMS);
            assertThat(secret.getSecretName()).isEqualTo("authKey");
            assertThat(secret.getCiphertext()).isEqualTo(CIPHERTEXT);
            assertThat(secret.getKeyVersion()).isEqualTo(1);
            assertThat(secret.getCreatedBy()).isEqualTo(ADMIN_USER);
        }

        @Test
        @DisplayName("Writing the same name again overwrites in place, keeping one row")
        void upsertOverwrites() {
            TenantProviderSecretDTO first = givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);
            TenantProviderSecretDTO second = repository.upsertSecret(
                    TENANT_MP, MessagingChannel.SMS, "authKey", "bmV3Y2lwaGVydGV4dHZhbHVl", 1, ADMIN_USER);

            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(second.getUuid()).isEqualTo(first.getUuid());
            assertThat(second.getCiphertext()).isEqualTo("bmV3Y2lwaGVydGV4dHZhbHVl");
            assertThat(repository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).hasSize(1);
        }

        @Test
        @DisplayName("A key version with no matching key row is refused by the composite foreign key")
        void secretRequiresItsOwnTenantsKeyVersion() {
            // The AAD includes key_version, so a row pointing at a version that does not exist
            // would be undecryptable. The FK makes it unstorable instead.
            assertThatThrownBy(() -> givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 7))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Reads are scoped to one tenant and one channel")
        void readsAreScoped() {
            givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);
            givenSecret(TENANT_MP, MessagingChannel.SMS, "authToken", 1);
            givenSecret(TENANT_MP, MessagingChannel.EMAIL, "apiKey", 1);
            givenSecret(TENANT_TR, MessagingChannel.SMS, "authKey", 1);

            assertThat(repository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS))
                    .extracting(TenantProviderSecretDTO::getSecretName)
                    .containsExactly("authKey", "authToken");
            assertThat(repository.findSecretNames(TENANT_MP, MessagingChannel.EMAIL))
                    .containsExactly("apiKey");
            assertThat(repository.findByTenant(TENANT_MP)).hasSize(3);
            assertThat(repository.findByTenant(TENANT_TR)).hasSize(1);
        }

        @Test
        @DisplayName("The same name on two channels is two independent rows")
        void nameIsScopedToTheChannel() {
            givenSecret(TENANT_MP, MessagingChannel.EMAIL, "apiKey", 1);
            givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);

            assertThat(repository.findByTenant(TENANT_MP))
                    .extracting(TenantProviderSecretDTO::getChannel, TenantProviderSecretDTO::getSecretName)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(MessagingChannel.EMAIL, "apiKey"),
                            org.assertj.core.groups.Tuple.tuple(MessagingChannel.SMS, "authKey"));
        }

        @Test
        @DisplayName("Soft-deleted secrets are invisible to every read")
        void softDeleteHidesFromReads() {
            givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);
            givenSecret(TENANT_MP, MessagingChannel.SMS, "authToken", 1);
            givenSecret(TENANT_MP, MessagingChannel.EMAIL, "apiKey", 1);

            int deleted = repository.softDeleteByTenantAndChannel(TENANT_MP, MessagingChannel.SMS, ADMIN_USER);

            assertThat(deleted).isEqualTo(2);
            assertThat(repository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).isEmpty();
            assertThat(repository.findSecretNames(TENANT_MP, MessagingChannel.SMS)).isEmpty();
            // The other channel is untouched.
            assertThat(repository.findByTenant(TENANT_MP)).hasSize(1);
        }

        @Test
        @DisplayName("A soft delete records who removed the credential, and keeps the row")
        void softDeleteIsAuditable() {
            givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);
            repository.softDeleteByTenantAndChannel(TENANT_MP, MessagingChannel.SMS, ADMIN_USER);

            assertThat(jdbcTemplate.queryForObject("""
                    SELECT deleted_by FROM common_schema.tenant_provider_secret
                    WHERE tenant_id = ? AND deleted_at IS NOT NULL
                    """, Integer.class, TENANT_MP)).isEqualTo(ADMIN_USER);
        }

        @Test
        @DisplayName("Deleting a channel with nothing stored reports zero rows")
        void softDeleteOfNothing() {
            assertThat(repository.softDeleteByTenantAndChannel(TENANT_MP, MessagingChannel.SMS, ADMIN_USER))
                    .isZero();
        }

        @Test
        @DisplayName("Writing a name again after a soft delete revives the row rather than failing")
        void upsertRevivesSoftDeletedRow() {
            // uq_tenant_provider_secret is unconditional, so the insert collides with the dead
            // row. Without the DO UPDATE clearing deleted_at, re-adding a credential a tenant had
            // removed would be impossible.
            TenantProviderSecretDTO original = givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);
            repository.softDeleteByTenantAndChannel(TENANT_MP, MessagingChannel.SMS, ADMIN_USER);

            TenantProviderSecretDTO revived = repository.upsertSecret(
                    TENANT_MP, MessagingChannel.SMS, "authKey", "cmV2aXZlZGNpcGhlcnRleHQ=", 1, ADMIN_USER);

            assertThat(revived.getId()).isEqualTo(original.getId());
            assertThat(revived.getCiphertext()).isEqualTo("cmV2aXZlZGNpcGhlcnRleHQ=");
            assertThat(repository.findSecretNames(TENANT_MP, MessagingChannel.SMS)).containsExactly("authKey");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.tenant_provider_secret WHERE tenant_id = ?",
                    Integer.class, TENANT_MP)).isEqualTo(1);
        }

        @Test
        @DisplayName("Re-encryption moves a secret to a new key version")
        void updateCiphertextMovesKeyVersion() {
            TenantProviderSecretDTO secret = givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);
            TenantSecretKeyDTO active = repository.findActiveKey(TENANT_MP).orElseThrow();
            repository.retireKey(active.getId(), ADMIN_USER);
            givenActiveKey(TENANT_MP, 2);

            assertThat(repository.updateCiphertext(secret.getId(), "cm90YXRlZGNpcGhlcnRleHQ=", 2, ADMIN_USER))
                    .isEqualTo(1);

            TenantProviderSecretDTO reloaded = repository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)
                    .get(0);
            assertThat(reloaded.getKeyVersion()).isEqualTo(2);
            assertThat(reloaded.getCiphertext()).isEqualTo("cm90YXRlZGNpcGhlcnRleHQ=");
        }

        @Test
        @DisplayName("Re-encryption skips a soft-deleted row")
        void updateCiphertextIgnoresDeleted() {
            TenantProviderSecretDTO secret = givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);
            repository.softDeleteByTenantAndChannel(TENANT_MP, MessagingChannel.SMS, ADMIN_USER);

            assertThat(repository.updateCiphertext(secret.getId(), "c29tZXRoaW5nZWxzZQ==", 1, ADMIN_USER))
                    .isZero();
        }

        @Test
        @DisplayName("toString() renders the row's identity and never the ciphertext")
        void toStringOmitsCiphertext() {
            TenantProviderSecretDTO secret = givenSecret(TENANT_MP, MessagingChannel.SMS, "authKey", 1);

            assertThat(secret.toString())
                    .contains("tenantId=101", "channel=SMS", "secretName=authKey", "keyVersion=1")
                    .doesNotContain(CIPHERTEXT);
        }
    }
}
