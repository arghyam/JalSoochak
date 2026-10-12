package org.arghyam.jalsoochak.message.ledger;

/**
 * What a ledgered message is for — the {@code message_type} of a {@code notification_table} row.
 *
 * <p>One value per thing a recipient receives, not per Kafka event: the two welcome events both
 * record {@link #WELCOME}, and the raw event type is kept beside it in {@code event_type}. Events that
 * send nothing — a contact-language update, a staff sync's opt-ins — have no value here and are never
 * recorded.</p>
 */
public enum NotificationType {
    NUDGE,
    ESCALATION,
    DAILY_REPORT,
    WEEKLY_REPORT,
    WELCOME,
    LOGIN_OTP,
    INVITE,
    REINVITE,
    PASSWORD_RESET
}
