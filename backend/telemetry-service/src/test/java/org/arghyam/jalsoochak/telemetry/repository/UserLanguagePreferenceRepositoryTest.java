package org.arghyam.jalsoochak.telemetry.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Per-contact language preferences in each tenant schema. Contact ids reach us in several shapes
 * ({@code +91 99999-00001}, {@code 919999900001}); every write stores, and every lookup matches, the
 * digits-only form. The SQL itself is exercised against PostgreSQL in
 * {@link UserPreferenceRepositoriesIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserLanguagePreferenceRepository")
class UserLanguagePreferenceRepositoryTest {

    private static final String SCHEMA = "tenant_as";
    private static final String RAW_CONTACT = "+91 99999-00001";
    private static final String NORMALISED_CONTACT = "919999900001";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    private UserLanguagePreferenceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new UserLanguagePreferenceRepository(jdbcTemplate, telemetryTenantRepository);
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        lenient().when(telemetryTenantRepository.findTenantSchemasWithColumn("user_language_preference", "contact_id"))
                .thenReturn(List.of("tenant_as", "tenant_mp"));
    }

    private String capturedQuerySql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return sql.getValue();
    }

    private Object[] capturedQueryArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());
        return args.getValue();
    }

    @Test
    void upsertStoresTheDigitsOnlyContactId() {
        repository.upsert(SCHEMA, RAW_CONTACT, "Hindi");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()).containsExactly(NORMALISED_CONTACT, "Hindi");
    }

    @Test
    void upsertWritesTheTenantTableAndUpdatesInPlaceOnConflict() {
        repository.upsert(SCHEMA, RAW_CONTACT, "Hindi");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("INSERT INTO tenant_as.user_language_preference")
                .contains("ON CONFLICT (contact_id)")
                .contains("DO UPDATE SET language_value = EXCLUDED.language_value");
    }

    @Test
    void upsertToleratesANullContactId() {
        repository.upsert(SCHEMA, null, "Hindi");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()).containsExactly(null, "Hindi");
    }

    @Test
    void findLanguageLooksUpTheNormalisedContactIdInTheTenantTable() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("Hindi"));

        assertThat(repository.findLanguage(SCHEMA, RAW_CONTACT)).contains("Hindi");
        assertThat(capturedQuerySql()).contains("FROM tenant_as.user_language_preference");
        assertThat(capturedQueryArgs()).containsExactly(NORMALISED_CONTACT);
    }

    @Test
    void findLanguageIsEmptyWhenNoPreferenceIsStored() {
        assertThat(repository.findLanguage(SCHEMA, RAW_CONTACT)).isEmpty();
    }

    @Test
    void rejectsASchemaNameThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> repository.upsert("Tenant_AS", RAW_CONTACT, "Hindi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schema name");
        assertThatThrownBy(() -> repository.findLanguage("tenant_as; DROP TABLE x", RAW_CONTACT))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void findPreferredTenantIdQueriesEveryTenantSchemaThatHasTheTableInOneStatement() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(2));

        assertThat(repository.findPreferredTenantIdByContactId(RAW_CONTACT)).contains(2);
        assertThat(capturedQuerySql())
                .contains("FROM tenant_as.user_language_preference")
                .contains("UNION ALL")
                .contains("FROM tenant_mp.user_language_preference")
                .contains("ORDER BY preference.updated_at DESC, preference.created_at DESC");
        assertThat(capturedQueryArgs())
                .containsExactly("tenant_as", NORMALISED_CONTACT, "tenant_mp", NORMALISED_CONTACT);
    }

    @Test
    void findPreferredTenantIdIsEmptyWithoutQueryingWhenNoSchemaHasTheTable() {
        when(telemetryTenantRepository.findTenantSchemasWithColumn("user_language_preference", "contact_id"))
                .thenReturn(List.of());

        assertThat(repository.findPreferredTenantIdByContactId(RAW_CONTACT)).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void findPreferredTenantIdIsEmptyWithoutQueryingForAMissingOrDigitlessContact() {
        assertThat(repository.findPreferredTenantIdByContactId(null)).isEmpty();
        assertThat(repository.findPreferredTenantIdByContactId("+-")).isEmpty();
        verifyNoInteractions(jdbcTemplate, telemetryTenantRepository);
    }

    @Test
    void findPreferredTenantIdRejectsATenantSchemaNameThatIsNotAPlainIdentifier() {
        when(telemetryTenantRepository.findTenantSchemasWithColumn("user_language_preference", "contact_id"))
                .thenReturn(List.of("tenant_as", "tenant_Mp"));

        assertThatThrownBy(() -> repository.findPreferredTenantIdByContactId(RAW_CONTACT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schema name");
        verifyNoInteractions(jdbcTemplate);
    }
}
