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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-contact language preferences in {@code common_schema}. Contact ids reach us in several shapes
 * ({@code +91 99999-00001}, {@code 919999900001}), so every lookup also matches on the digits-only
 * form and every write stores that normalised form.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserLanguagePreferenceRepository")
class UserLanguagePreferenceRepositoryTest {

    private static final String RAW_CONTACT = "+91 99999-00001";
    private static final String NORMALISED_CONTACT = "919999900001";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private UserLanguagePreferenceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new UserLanguagePreferenceRepository(jdbcTemplate);
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
    }

    private Object[] capturedQueryArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());
        return args.getValue();
    }

    @Test
    void upsertStoresTheDigitsOnlyContactId() {
        repository.upsert(17, RAW_CONTACT, "Hindi");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()).containsExactly(17, NORMALISED_CONTACT, "Hindi");
    }

    @Test
    void upsertUpdatesInPlaceOnConflict() {
        repository.upsert(17, RAW_CONTACT, "Hindi");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (tenant_id, contact_id)")
                .contains("DO UPDATE SET language_value = EXCLUDED.language_value");
    }

    @Test
    void upsertToleratesANullContactId() {
        repository.upsert(17, null, "Hindi");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()).containsExactly(17, null, "Hindi");
    }

    @Test
    void findLanguageMatchesTheRawAndNormalisedContactId() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("Hindi"));

        assertThat(repository.findLanguage(17, RAW_CONTACT)).contains("Hindi");
        assertThat(capturedQueryArgs()).containsExactly(17, RAW_CONTACT, NORMALISED_CONTACT);
    }

    @Test
    void findLanguageIsEmptyWhenNoPreferenceIsStored() {
        assertThat(repository.findLanguage(17, RAW_CONTACT)).isEmpty();
    }

    @Test
    void findPreferredTenantIdMatchesTheRawAndNormalisedContactId() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(17));

        assertThat(repository.findPreferredTenantIdByContactId(RAW_CONTACT)).contains(17);
        assertThat(capturedQueryArgs()).containsExactly(RAW_CONTACT, NORMALISED_CONTACT);
    }

    @Test
    void findPreferredTenantIdPrefersTheMostRecentlyUpdatedPreference() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(17, 18));

        assertThat(repository.findPreferredTenantIdByContactId(RAW_CONTACT)).contains(17);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("ORDER BY updated_at DESC NULLS LAST");
    }

    @Test
    void findPreferredTenantIdIsEmptyWhenTheContactIsUnknown() {
        assertThat(repository.findPreferredTenantIdByContactId(RAW_CONTACT)).isEmpty();
    }

    @Test
    void findPreferredTenantIdToleratesANullContactId() {
        assertThat(repository.findPreferredTenantIdByContactId(null)).isEmpty();
        assertThat(capturedQueryArgs()).containsExactly(null, null);
    }
}
