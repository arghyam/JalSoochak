package org.arghyam.jalsoochak.analytics.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the numeric channel-code contract shared with telemetry-service over Kafka
 * ({@code MeterReadingEvent.channel}). The two services keep independent copies of this
 * enum; if either side's code mapping drifts, this test (and its telemetry twin
 * {@code ReadingChannelTest.codes_areStableAndDistinct}) must fail so the mismatch is
 * caught before a reading is routed to the wrong calculator.
 */
class ReadingChannelTest {

    @Test
    void codes_matchTheCrossServiceWireContract() {
        assertThat(ReadingChannel.BFM.getCode()).isEqualTo(1);
        assertThat(ReadingChannel.ELM.getCode()).isEqualTo(2);
        assertThat(ReadingChannel.PDU.getCode()).isEqualTo(3);
        assertThat(ReadingChannel.IOT.getCode()).isEqualTo(4);
        assertThat(ReadingChannel.MAN.getCode()).isEqualTo(5);
    }

    @Test
    void fromCode_roundTripsEveryChannel() {
        for (ReadingChannel channel : ReadingChannel.values()) {
            assertThat(ReadingChannel.fromCode(channel.getCode())).isEqualTo(channel);
        }
    }

    @Test
    void fromCode_nullOrUnknown_defaultsToBfm() {
        assertThat(ReadingChannel.fromCode(null)).isEqualTo(ReadingChannel.BFM);
        assertThat(ReadingChannel.fromCode(999)).isEqualTo(ReadingChannel.BFM);
        assertThat(ReadingChannel.DEFAULT).isEqualTo(ReadingChannel.BFM);
    }
}
