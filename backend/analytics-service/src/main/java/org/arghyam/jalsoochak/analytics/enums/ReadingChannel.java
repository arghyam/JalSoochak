package org.arghyam.jalsoochak.analytics.enums;

import java.util.Arrays;

/**
 * Reading-submission channel (a.k.a. communication / meter channel) carried on
 * {@code MeterReadingEvent.channel}. Drives the per-channel analytics processing
 * (e.g. how water quantity is derived from a meter reading).
 *
 * <p>The short code (enum name) mirrors the allowed channel codes validated in
 * tenant-service ({@code ChannelValidator}: BFM, ELM, PDU, IOT, MAN). The numeric
 * {@link #getCode() code} is the app-level constant shared by convention with the
 * telemetry-service producer. {@link #BFM} (bulk flow meter) is the default and
 * reproduces the historical cumulative-delta behaviour for legacy events whose
 * {@code channel} is {@code null}.
 */
public enum ReadingChannel {
    BFM(1, "Bulk Flow Meter"),
    ELM(2, "Electric Meter"),
    PDU(3, "Pump Duration"),
    IOT(4, "IoT"),
    MAN(5, "Manual");

    public static final ReadingChannel DEFAULT = BFM;

    private final int code;
    private final String displayName;

    ReadingChannel(int code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public int getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Resolves a numeric channel code to its enum value, defaulting to {@link #BFM}
     * for {@code null} or unknown codes so legacy events (channel == null) keep
     * their existing behaviour.
     */
    public static ReadingChannel fromCode(Integer code) {
        if (code == null) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(channel -> channel.code == code)
                .findFirst()
                .orElse(DEFAULT);
    }
}
