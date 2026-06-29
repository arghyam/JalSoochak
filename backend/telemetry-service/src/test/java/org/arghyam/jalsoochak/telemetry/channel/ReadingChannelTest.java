package org.arghyam.jalsoochak.telemetry.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingChannelTest {

    @Test
    void fromChannelValue_nullOrBlank_defaultsToBfm() {
        assertThat(ReadingChannel.fromChannelValue(null)).isEqualTo(ReadingChannel.BFM);
        assertThat(ReadingChannel.fromChannelValue("   ")).isEqualTo(ReadingChannel.BFM);
    }

    @Test
    void fromChannelValue_matchesCanonicalShortCodeCaseInsensitively() {
        assertThat(ReadingChannel.fromChannelValue("BFM")).isEqualTo(ReadingChannel.BFM);
        assertThat(ReadingChannel.fromChannelValue("elm")).isEqualTo(ReadingChannel.ELM);
        assertThat(ReadingChannel.fromChannelValue(" PDU ")).isEqualTo(ReadingChannel.PDU);
        assertThat(ReadingChannel.fromChannelValue("IOT")).isEqualTo(ReadingChannel.IOT);
        assertThat(ReadingChannel.fromChannelValue("MAN")).isEqualTo(ReadingChannel.MAN);
    }

    @Test
    void fromChannelValue_matchesLegacyLabels() {
        assertThat(ReadingChannel.fromChannelValue("Electric Meter")).isEqualTo(ReadingChannel.ELM);
        assertThat(ReadingChannel.fromChannelValue("Pump Duration")).isEqualTo(ReadingChannel.PDU);
        assertThat(ReadingChannel.fromChannelValue("Manual Reading")).isEqualTo(ReadingChannel.MAN);
    }

    @Test
    void fromChannelValue_unrecognised_defaultsToBfm() {
        assertThat(ReadingChannel.fromChannelValue("something-else")).isEqualTo(ReadingChannel.BFM);
    }

    @Test
    void codes_areStableAndDistinct() {
        assertThat(ReadingChannel.BFM.getCode()).isEqualTo(1);
        assertThat(ReadingChannel.ELM.getCode()).isEqualTo(2);
        assertThat(ReadingChannel.PDU.getCode()).isEqualTo(3);
        assertThat(ReadingChannel.IOT.getCode()).isEqualTo(4);
        assertThat(ReadingChannel.MAN.getCode()).isEqualTo(5);
    }
}
