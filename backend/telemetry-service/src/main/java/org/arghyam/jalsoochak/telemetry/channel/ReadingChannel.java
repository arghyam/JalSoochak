package org.arghyam.jalsoochak.telemetry.channel;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

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
        // Exact match against known legacy display labels (compacted), e.g. "ELECTRICMETER".
        for (ReadingChannel channel : values()) {
            String compactLabel = channel.displayName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "");
            if (compactLabel.equals(compact)) {
                return channel;
            }
        }
        // Keyword fallbacks for free-form legacy values; anything else falls through to DEFAULT.
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

    /**
     * Whether a caller actually declared a channel. Null and blank both read as "not declared", so a
     * field left out and a field sent empty behave the same: the channel is resolved from the
     * operator's stored preference as before.
     */
    public static boolean isDeclared(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Strict counterpart to {@link #fromChannelValue(String)}, for a channel supplied by an API
     * caller. Accepts only the canonical short codes (trimmed, case-insensitive) and returns
     * {@link Optional#empty()} for everything else, including null/blank.
     *
     * <p><b>Why not reuse {@code fromChannelValue}.</b> That method is deliberately tolerant and maps
     * anything it does not recognise to BFM, because it parses free-form values already written to
     * {@code user_channel_preference}. It therefore cannot report an unsupported value — a typo would
     * be processed as a bulk-flow-meter reading. A caller declaring a channel from a published list
     * gets the strict reading instead, so the mistake surfaces as an error rather than as a wrong
     * water volume.
     */
    public static Optional<ReadingChannel> parseStrict(String value) {
        if (!isDeclared(value)) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(channel -> channel.name().equals(normalized))
                .findFirst();
    }

    /**
     * Whether a caller declared a channel that is not one of the canonical codes. A channel that was
     * not declared at all is not "unsupported" — it simply leaves the channel to be resolved from the
     * operator's stored preference.
     */
    public static boolean isUnsupportedDeclaration(String value) {
        return isDeclared(value) && parseStrict(value).isEmpty();
    }

    /**
     * The message returned with {@code CHANNEL_NOT_SUPPORTED}. Shared by every ingestion endpoint so
     * they cannot drift, and deliberately free of the submitted value: echoing caller input back into
     * a response body is how reflected content reaches a consumer that renders it.
     */
    public static String unsupportedDeclarationMessage() {
        return "Unsupported channel. Allowed values are: " + allowedValues();
    }

    /** The accepted codes, in declaration order, for error messages and API documentation. */
    public static String allowedValues() {
        return Arrays.stream(values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
