package org.arghyam.jalsoochak.telemetry.channel;

/**
 * A unit a reading can be submitted in, named by its UCUM code and tied to the one channel it
 * measures.
 *
 * <p>{@code flow_reading_table.confirmed_reading} always holds the channel's
 * {@linkplain ReadingChannel#standardUnit() standard unit}, because queries do arithmetic on that
 * column directly. {@code flow_reading_table.submitted_unit} stores {@link #code()} of the unit the
 * value arrived in.
 */
public enum ReadingUnit {
    CUBIC_METRE("m3", ReadingChannel.BFM),
    KILOLITRE("kL", ReadingChannel.BFM),
    LITRE("L", ReadingChannel.BFM),
    KILOWATT_HOUR("kW.h", ReadingChannel.ELM),
    MINUTE("min", ReadingChannel.PDU),
    HOUR("h", ReadingChannel.PDU);

    private final String code;
    private final ReadingChannel channel;

    ReadingUnit(String code, ReadingChannel channel) {
        this.code = code;
        this.channel = channel;
    }

    /** The UCUM spelling, which is what gets stored. */
    public String code() {
        return code;
    }

    /** The channel whose readings this unit measures. */
    public ReadingChannel channel() {
        return channel;
    }
}
