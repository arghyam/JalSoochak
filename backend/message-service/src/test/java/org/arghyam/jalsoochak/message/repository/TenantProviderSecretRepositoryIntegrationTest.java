package org.arghyam.jalsoochak.message.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.arghyam.jalsoochak.message.channel.GlificAuthService;
import org.arghyam.jalsoochak.message.channel.GlificWhatsAppService;
import org.arghyam.jalsoochak.message.dto.TenantProviderSecretRow;
import org.arghyam.jalsoochak.message.dto.TenantSecretKeyRow;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PER-TENANT-PROVIDERS: integration tests for {@link TenantProviderSecretRepository} against a real
 * PostgreSQL.
 *
 * <p>The rows are inserted with plain SQL rather than through tenant-service's writer, so what is
 * exercised is this service's reading of the V44 tables as they exist on disk — including the
 * {@code deleted_at IS NULL} predicate and the partial unique index on the active key.
 *
 * <p>The ciphertexts here are not real: nothing in this class decrypts, and a repository that
 * needed valid ciphertext to return a row would be doing something it should not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("TenantProviderSecretRepository Integration Tests")
class TenantProviderSecretRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean
    private GlificAuthService glificAuthService;

    @MockBean
    private GlificWhatsAppService glificWhatsAppService;

    @Autowired
    private TenantProviderSecretRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT_A = 1;
    private static final int TENANT_B = 2;

    @AfterEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_provider_secret");
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_secret_key");
    }

    private void insertKey(int tenantId, int keyVersion, String masterKeyId, String status) {
        jdbcTemplate.update("INSERT INTO common_schema.tenant_secret_key"
                + " (tenant_id, key_version, wrapped_key, master_key_id, status) VALUES (?, ?, ?, ?, ?)",
                tenantId, keyVersion, "wrapped-" + tenantId + "-" + keyVersion, masterKeyId, status);
    }

    private void insertSecret(int tenantId, MessagingChannel channel, String name, int keyVersion) {
        jdbcTemplate.update("INSERT INTO common_schema.tenant_provider_secret"
                + " (tenant_id, channel, secret_name, ciphertext, key_version) VALUES (?, ?, ?, ?, ?)",
                tenantId, channel.name(), name, "ciphertext-" + tenantId + "-" + name, keyVersion);
    }

    // ── keys ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the active key is found by tenant")
    void activeKeyIsFoundByTenant() {
        insertKey(TENANT_A, 1, "v1", "ACTIVE");

        TenantSecretKeyRow key = repository.findActiveKey(TENANT_A).orElseThrow();

        assertThat(key.tenantId()).isEqualTo(TENANT_A);
        assertThat(key.keyVersion()).isEqualTo(1);
        assertThat(key.masterKeyId()).isEqualTo("v1");
        assertThat(key.status()).isEqualTo("ACTIVE");
        assertThat(key.wrappedKey()).isEqualTo("wrapped-1-1");
    }

    @Test
    @DisplayName("a retired key version is still findable by version")
    void retiredKeyIsFindableByVersion() {
        // Retired means "no longer used for new writes". A secret still pointing at it has to stay
        // readable, or a send landing mid-rotation fails for a reason nobody caused.
        insertKey(TENANT_A, 1, "v1", "RETIRED");
        insertKey(TENANT_A, 2, "v1", "ACTIVE");

        assertThat(repository.findKey(TENANT_A, 1).orElseThrow().status()).isEqualTo("RETIRED");
        assertThat(repository.findActiveKey(TENANT_A).orElseThrow().keyVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("a tenant with no key reads as empty")
    void tenantWithNoKeyReadsAsEmpty() {
        assertThat(repository.findActiveKey(TENANT_A)).isEmpty();
        assertThat(repository.findKey(TENANT_A, 1)).isEmpty();
    }

    @Test
    @DisplayName("keys are scoped to their tenant")
    void keysAreScopedToTheirTenant() {
        insertKey(TENANT_A, 1, "v1", "ACTIVE");

        assertThat(repository.findActiveKey(TENANT_B)).isEmpty();
    }

    @Test
    @DisplayName("a key row never prints its wrapped key")
    void keyRowNeverPrintsItsWrappedKey() {
        insertKey(TENANT_A, 1, "v1", "ACTIVE");

        assertThat(repository.findActiveKey(TENANT_A).orElseThrow().toString())
                .doesNotContain("wrapped-1-1")
                .contains("tenantId=1")
                .contains("masterKeyId=v1");
    }

    // ── secrets ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a channel's live secrets come back in one read")
    void liveSecretsComeBackInOneRead() {
        insertKey(TENANT_A, 1, "v1", "ACTIVE");
        insertSecret(TENANT_A, MessagingChannel.SMS, "authKey", 1);
        insertSecret(TENANT_A, MessagingChannel.SMS, "authToken", 1);

        List<TenantProviderSecretRow> rows =
                repository.findByTenantAndChannel(TENANT_A, MessagingChannel.SMS);

        assertThat(rows).extracting(TenantProviderSecretRow::secretName)
                .containsExactly("authKey", "authToken");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo(TENANT_A);
            assertThat(row.channel()).isEqualTo(MessagingChannel.SMS);
            assertThat(row.keyVersion()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a soft-deleted secret is invisible to both reads")
    void softDeletedSecretIsInvisible() {
        insertKey(TENANT_A, 1, "v1", "ACTIVE");
        insertSecret(TENANT_A, MessagingChannel.EMAIL, "apiKey", 1);
        jdbcTemplate.update("UPDATE common_schema.tenant_provider_secret SET deleted_at = NOW()"
                + " WHERE tenant_id = ? AND secret_name = ?", TENANT_A, "apiKey");

        assertThat(repository.findByTenantAndChannel(TENANT_A, MessagingChannel.EMAIL)).isEmpty();
        assertThat(repository.findSecret(TENANT_A, MessagingChannel.EMAIL, "apiKey")).isEmpty();
    }

    @Test
    @DisplayName("secrets are scoped to their tenant and channel")
    void secretsAreScopedToTheirTenantAndChannel() {
        insertKey(TENANT_A, 1, "v1", "ACTIVE");
        insertKey(TENANT_B, 1, "v1", "ACTIVE");
        insertSecret(TENANT_A, MessagingChannel.EMAIL, "apiKey", 1);
        insertSecret(TENANT_B, MessagingChannel.EMAIL, "apiKey", 1);

        assertThat(repository.findSecret(TENANT_A, MessagingChannel.EMAIL, "apiKey").orElseThrow()
                .ciphertext()).isEqualTo("ciphertext-1-apiKey");
        assertThat(repository.findSecret(TENANT_B, MessagingChannel.EMAIL, "apiKey").orElseThrow()
                .ciphertext()).isEqualTo("ciphertext-2-apiKey");
        // Same name, other channel: no row.
        assertThat(repository.findSecret(TENANT_A, MessagingChannel.SMS, "apiKey")).isEmpty();
    }

    @Test
    @DisplayName("a secret still pointing at a retired key version is returned with that version")
    void secretCarriesItsOwnKeyVersion() {
        insertKey(TENANT_A, 1, "v1", "RETIRED");
        insertKey(TENANT_A, 2, "v1", "ACTIVE");
        insertSecret(TENANT_A, MessagingChannel.EMAIL, "apiKey", 1);

        // The version on the row, not the active one: it is part of the AAD (S-7).
        assertThat(repository.findSecret(TENANT_A, MessagingChannel.EMAIL, "apiKey").orElseThrow()
                .keyVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("a secret row never prints its ciphertext")
    void secretRowNeverPrintsItsCiphertext() {
        insertKey(TENANT_A, 1, "v1", "ACTIVE");
        insertSecret(TENANT_A, MessagingChannel.EMAIL, "apiKey", 1);

        assertThat(repository.findSecret(TENANT_A, MessagingChannel.EMAIL, "apiKey").orElseThrow()
                .toString())
                .doesNotContain("ciphertext-1-apiKey")
                .contains("secretName=apiKey")
                .contains("keyVersion=1");
    }

    @Test
    @DisplayName("the partial unique index permits only one ACTIVE key per tenant")
    void onlyOneActiveKeyPerTenant() {
        insertKey(TENANT_A, 1, "v1", "ACTIVE");

        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> insertKey(TENANT_A, 2, "v1", "ACTIVE")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
