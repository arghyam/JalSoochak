package org.arghyam.jalsoochak.telemetry.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantSupportedChannelsTest {

    private static final int TENANT_ID = 7;
    private static final String CONFIG_KEY = "TENANT_SUPPORTED_CHANNELS";

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    private TenantSupportedChannels tenantSupportedChannels;

    @BeforeEach
    void setUp() {
        tenantSupportedChannels = new TenantSupportedChannels(tenantConfigRepository, new ObjectMapper());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"channels\":[\"ELM\"]}",
            "[\"ELM\"]",
            "{\"channels\":\"ELM\"}",
            "{\"channels\":[\" elm \"]}"
    })
    void soleChannel_whenTenantListsOneChannel_returnsIt(String config) {
        when(tenantConfigRepository.findConfigValue(TENANT_ID, CONFIG_KEY)).thenReturn(Optional.of(config));

        assertThat(tenantSupportedChannels.soleChannel(TENANT_ID)).contains(ReadingChannel.ELM);
    }

    @Test
    void soleChannel_whenTheOneChannelIsListedTwice_returnsIt() {
        when(tenantConfigRepository.findConfigValue(TENANT_ID, CONFIG_KEY))
                .thenReturn(Optional.of("{\"channels\":[\"PDU\",\"pdu\"]}"));

        assertThat(tenantSupportedChannels.soleChannel(TENANT_ID)).contains(ReadingChannel.PDU);
    }

    @Test
    void soleChannel_whenTenantListsSeveralChannels_isEmpty() {
        when(tenantConfigRepository.findConfigValue(TENANT_ID, CONFIG_KEY))
                .thenReturn(Optional.of("{\"channels\":[\"BFM\",\"ELM\"]}"));

        assertThat(tenantSupportedChannels.soleChannel(TENANT_ID)).isEmpty();
    }

    @Test
    void soleChannel_whenTheOneEntryIsNotAChannelCode_isEmpty() {
        when(tenantConfigRepository.findConfigValue(TENANT_ID, CONFIG_KEY))
                .thenReturn(Optional.of("{\"channels\":[\"Electric Meter\"]}"));

        assertThat(tenantSupportedChannels.soleChannel(TENANT_ID)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "null", "{\"channels\":[]}", "{\"channels\":[\"\", 5]}", "{\"other\":1}", "{not json"})
    void soleChannel_whenConfigListsNoUsableChannel_isEmpty(String config) {
        when(tenantConfigRepository.findConfigValue(TENANT_ID, CONFIG_KEY)).thenReturn(Optional.of(config));

        assertThat(tenantSupportedChannels.soleChannel(TENANT_ID)).isEmpty();
    }

    @Test
    void soleChannel_whenTenantHasNoConfig_isEmpty() {
        when(tenantConfigRepository.findConfigValue(TENANT_ID, CONFIG_KEY)).thenReturn(Optional.empty());

        assertThat(tenantSupportedChannels.soleChannel(TENANT_ID)).isEmpty();
    }

    @Test
    void soleChannel_whenTenantIdMissing_isEmptyWithoutLookup() {
        assertThat(tenantSupportedChannels.soleChannel(null)).isEmpty();
        verifyNoInteractions(tenantConfigRepository);
    }
}
