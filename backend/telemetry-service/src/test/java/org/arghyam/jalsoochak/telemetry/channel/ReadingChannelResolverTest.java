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

    private static final String SCHEMA = "tenant_as";

    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;

    @InjectMocks
    private ReadingChannelResolver resolver;

    @Test
    void resolve_whenPreferencePresent_mapsStoredChannelValue() {
        when(userChannelPreferenceRepository.findChannelValue(SCHEMA, "919999999999"))
                .thenReturn(Optional.of("ELM"));

        assertThat(resolver.resolve(SCHEMA, "919999999999")).isEqualTo(ReadingChannel.ELM);
        assertThat(resolver.resolveCode(SCHEMA, "919999999999")).isEqualTo(ReadingChannel.ELM.getCode());
    }

    @Test
    void resolve_whenNoPreference_defaultsToBfm() {
        when(userChannelPreferenceRepository.findChannelValue(SCHEMA, "919999999999"))
                .thenReturn(Optional.empty());

        assertThat(resolver.resolve(SCHEMA, "919999999999")).isEqualTo(ReadingChannel.BFM);
    }

    @Test
    void resolve_whenSchemaOrContactMissing_defaultsToBfmWithoutLookup() {
        assertThat(resolver.resolve(null, "919999999999")).isEqualTo(ReadingChannel.BFM);
        assertThat(resolver.resolve(SCHEMA, null)).isEqualTo(ReadingChannel.BFM);
        assertThat(resolver.resolve(SCHEMA, "  ")).isEqualTo(ReadingChannel.BFM);
        verifyNoInteractions(userChannelPreferenceRepository);
    }
}
