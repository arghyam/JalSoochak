package org.arghyam.jalsoochak.telemetry.channel;

/**
 * The data-collection channel a submission reached the service through, as opposed to the
 * {@link ReadingChannel} its reading came from. Stored in {@code flow_reading_table.reported_via_id}.
 *
 * <p>The numeric {@link #getCode() code} is the row's id in {@code common_schema.channel_master_table},
 * where these channels have {@code channel_type} 2, seeded by V61.
 */
public enum ReportingChannel {
    /** The WhatsApp conversation, through the chatbot webhook routes. */
    WHATSAPP(6),
    /** A state IT system, through the routes authenticated by a tenant API key. */
    API(7);

    private final int code;

    ReportingChannel(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
