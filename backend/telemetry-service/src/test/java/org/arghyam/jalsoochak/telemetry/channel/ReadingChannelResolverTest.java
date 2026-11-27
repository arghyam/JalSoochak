package org.arghyam.jalsoochak.telemetry.channel;

import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingChannelResolverTest {

    private static final int TENANT_ID = 7;
    private static final String SCHEMA = "tenant_as";
    private static final String CONTACT = "919999999999";

    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;

    @Mock
    private TenantSupportedChannels tenantSupportedChannels;

    @InjectMocks
    private ReadingChannelResolver resolver;

    @Test
    void resolve_whenTenantSupportsOneChannel_returnsItWithoutReadingThePreference() {
        when(tenantSupportedChannels.soleChannel(TENANT_ID)).thenReturn(Optional.of(ReadingChannel.ELM));

        assertThat(resolver.resolve(TENANT_ID, SCHEMA, CONTACT)).isEqualTo(ReadingChannel.ELM);
        verifyNoInteractions(userChannelPreferenceRepository);
    }

    @Test
    void resolve_whenTenantSupportsOneChannelAndContactMissing_returnsIt() {
        when(tenantSupportedChannels.soleChannel(TENANT_ID)).thenReturn(Optional.of(ReadingChannel.ELM));

        assertThat(resolver.resolve(TENANT_ID, SCHEMA, null)).isEqualTo(ReadingChannel.ELM);
    }

    @Test
    void resolve_whenTenantSupportsSeveralChannels_mapsStoredPreference() {
        when(tenantSupportedChannels.soleChannel(TENANT_ID)).thenReturn(Optional.empty());
        when(userChannelPreferenceRepository.findChannelValue(SCHEMA, CONTACT)).thenReturn(Optional.of("ELM"));

        assertThat(resolver.resolve(TENANT_ID, SCHEMA, CONTACT)).isEqualTo(ReadingChannel.ELM);
    }

    @Test
    void resolve_whenTenantSupportsSeveralChannelsAndNoPreference_defaultsToBfm() {
        when(tenantSupportedChannels.soleChannel(TENANT_ID)).thenReturn(Optional.empty());
        when(userChannelPreferenceRepository.findChannelValue(SCHEMA, CONTACT)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(TENANT_ID, SCHEMA, CONTACT)).isEqualTo(ReadingChannel.BFM);
    }

    @Test
    void resolve_whenTenantIdMissing_mapsStoredPreference() {
        when(tenantSupportedChannels.soleChannel(null)).thenReturn(Optional.empty());
        when(userChannelPreferenceRepository.findChannelValue(SCHEMA, CONTACT)).thenReturn(Optional.of("PDU"));

        assertThat(resolver.resolve(null, SCHEMA, CONTACT)).isEqualTo(ReadingChannel.PDU);
    }

    @Test
    void resolve_whenSchemaOrContactMissing_defaultsToBfmWithoutPreferenceLookup() {
        when(tenantSupportedChannels.soleChannel(TENANT_ID)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(TENANT_ID, null, CONTACT)).isEqualTo(ReadingChannel.BFM);
        assertThat(resolver.resolve(TENANT_ID, SCHEMA, null)).isEqualTo(ReadingChannel.BFM);
        assertThat(resolver.resolve(TENANT_ID, SCHEMA, "  ")).isEqualTo(ReadingChannel.BFM);
        verifyNoInteractions(userChannelPreferenceRepository);
    }
}
