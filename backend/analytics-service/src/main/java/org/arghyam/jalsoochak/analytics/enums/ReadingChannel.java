package org.arghyam.jalsoochak.analytics.enums;

import java.util.Arrays;
import java.util.Optional;

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
    BFM(1, "Bulk Flow Meter", ReadingKind.METER_INDEX),
    ELM(2, "Electric Meter", ReadingKind.METER_INDEX),
    PDU(3, "Pump Duration", ReadingKind.PERIOD_AMOUNT),
    IOT(4, "IoT", null),
    MAN(5, "Manual", null);

    public static final ReadingChannel DEFAULT = BFM;

    private final int code;
    private final String displayName;
    private final ReadingKind kind;

    ReadingChannel(int code, String displayName, ReadingKind kind) {
        this.code = code;
        this.displayName = displayName;
        this.kind = kind;
    }

    public int getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * What one of this channel's readings measures. Empty for a channel whose readings have no
     * defined meaning yet (IOT, MAN): water quantity is not worked out for them at all.
     */
    public Optional<ReadingKind> kind() {
        return Optional.ofNullable(kind);
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
