package org.arghyam.jalsoochak.message.ledger;

/**
 * The channel a ledgered message went out on, as the {@code common_schema.channel_master_table} id
 * stored in {@code notification_table.channel}. The ids are seeded by V61 (WHATSAPP) and V63 (EMAIL,
 * SMS), which fail unless each id carries this title.
 */
public enum LedgerChannel {
    WHATSAPP(6),
    EMAIL(8),
    SMS(9);

    private final int id;

    LedgerChannel(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** The channel for a stored id, or {@code null} for one this service does not send on. */
    public static LedgerChannel fromId(int id) {
        for (LedgerChannel channel : values()) {
            if (channel.id == id) {
                return channel;
            }
        }
        return null;
    }
}
