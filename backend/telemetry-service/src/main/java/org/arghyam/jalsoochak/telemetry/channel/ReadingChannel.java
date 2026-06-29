package org.arghyam.jalsoochak.telemetry.channel;

import java.util.Locale;

/**
 * Reading-submission channel (a.k.a. communication / meter channel) that a user
 * submits readings through. The short code (enum name) mirrors the allowed channel
 * codes validated in tenant-service ({@code ChannelValidator}: BFM, ELM, PDU, IOT, MAN)
 * and stored in {@code common_schema.user_channel_preference.channel_value}.
 *
 * <p>The numeric {@link #getCode() code} is an app-level constant attached to
 * {@code MeterReadingEvent.channel} and shared by convention with analytics-service,
 * which uses it to select the per-channel processing (e.g. water-quantity calculation).
 * {@link #BFM} (bulk flow meter) is the default whenever a channel cannot be resolved,
 * preserving the historical behaviour.
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
     * Maps a stored {@code channel_value} to a {@link ReadingChannel}, defaulting to
     * {@link #BFM} for null/blank/unrecognised values. Matching is tolerant: it accepts
     * the canonical short code (e.g. {@code "BFM"}) as well as common labels left over
     * from label-based tenant configs (e.g. {@code "Electric Meter"}).
     */
    public static ReadingChannel fromChannelValue(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ReadingChannel channel : values()) {
            if (channel.name().equals(normalized)) {
                return channel;
            }
        }

        String compact = normalized.replaceAll("[^A-Z0-9]+", "");
        for (ReadingChannel channel : values()) {
            if (compact.contains(channel.name())) {
                return channel;
            }
        }
        if (compact.contains("ELECTRIC")) {
            return ELM;
        }
        if (compact.contains("PUMP") || compact.contains("DURATION")) {
            return PDU;
        }
        if (compact.contains("MANUAL")) {
            return MAN;
        }
        return DEFAULT;
    }
}
