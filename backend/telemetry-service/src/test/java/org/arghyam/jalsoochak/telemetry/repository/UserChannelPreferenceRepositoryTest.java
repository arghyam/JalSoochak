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
 * Per-contact reading-channel (BFM/ELM) preferences in {@code common_schema}. Contact ids are stored
 * and looked up in their digits-only form so the several shapes Glific sends all resolve to one row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserChannelPreferenceRepository")
class UserChannelPreferenceRepositoryTest {

    private static final String RAW_CONTACT = "+91 99999-00001";
    private static final String NORMALISED_CONTACT = "919999900001";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private UserChannelPreferenceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new UserChannelPreferenceRepository(jdbcTemplate);
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
    }

    @Test
    void upsertStoresTheDigitsOnlyContactId() {
        repository.upsert(17, RAW_CONTACT, "BFM");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()).containsExactly(17, NORMALISED_CONTACT, "BFM");
    }

    @Test
    void upsertUpdatesInPlaceOnConflict() {
        repository.upsert(17, RAW_CONTACT, "ELM");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (tenant_id, contact_id)")
                .contains("DO UPDATE SET channel_value = EXCLUDED.channel_value");
    }

    @Test
    void upsertToleratesANullContactId() {
        repository.upsert(17, null, "BFM");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()).containsExactly(17, null, "BFM");
    }

    @Test
    void findChannelValueLooksUpTheNormalisedContactId() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("BFM"));

        assertThat(repository.findChannelValue(17, RAW_CONTACT)).contains("BFM");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());
        assertThat(args.getValue()).containsExactly(17, NORMALISED_CONTACT);
    }

    @Test
    void findChannelValueIsEmptyWhenNoPreferenceIsStored() {
        assertThat(repository.findChannelValue(17, RAW_CONTACT)).isEmpty();
    }

    @Test
    void findChannelValueToleratesANullContactId() {
        assertThat(repository.findChannelValue(17, null)).isEmpty();
    }
}
