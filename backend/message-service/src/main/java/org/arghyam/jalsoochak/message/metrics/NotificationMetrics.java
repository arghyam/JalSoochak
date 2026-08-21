package org.arghyam.jalsoochak.message.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counts the terminal outcome of every notification send attempt.
 *
 * <p>Before this existed the only delivery metric in the service was
 * {@code KafkaConfig#DEADLETTER_COUNTER}, which is incremented on the dead-letter paths alone. That
 * left the OTP channels completely uninstrumented: {@code otp-topic} is configured with
 * {@code FixedBackOff(0, 0)} and the SMS handler swallows its own errors, so an OTP failure never
 * reaches a recoverer and never touched a counter. An SMSCountry outage was therefore invisible to
 * monitoring — the first signal was a user reporting they could not log in.
 *
 * <p>Every increment carries the same three tag keys. Micrometer registers a counter's tag key set on
 * first use and Prometheus requires it to stay constant, so a path that omitted one would throw on
 * whichever call ran second and silently disarm the alerts this feeds. The constants below exist so
 * the vocabulary cannot drift between call sites.
 *
 * <h2>Outcome vocabulary</h2>
 * <ul>
 *   <li>{@link #SENT} — the provider accepted the message. Note this is acceptance, not delivery:
 *       neither SendGrid nor SMSCountry confirms the recipient received anything on this call.</li>
 *   <li>{@link #REJECTED} — the provider refused it, or we refused to send. Terminal; a replay would
 *       fail identically. Bad API key, malformed payload, a 4xx.</li>
 *   <li>{@link #FAILED} — transport or provider error. Not terminal for email (the container retries
 *       and this counter increments again per attempt, which is what makes the rate meaningful during
 *       an outage); terminal for OTP, which never retries.</li>
 *   <li>{@link #SKIPPED} — never attempted, because the event itself was unusable. Distinguishes a
 *       broken producer from a broken provider.</li>
 * </ul>
 */
@Component
public class NotificationMetrics {

    /**
     * Alert on it in Grafana. The OTP channels are the ones that need it most: they have no retry and
     * no dead-letter topic, so this counter is the only trace a failed login OTP leaves behind.
     */
    public static final String SEND_COUNTER = "jalsoochak.notification.send";

    public static final String CHANNEL_SMS = "SMS";
    public static final String CHANNEL_WHATSAPP = "WHATSAPP";
    public static final String CHANNEL_EMAIL = "EMAIL";

    public static final String SENT = "sent";
    public static final String REJECTED = "rejected";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    private final MeterRegistry meterRegistry;

    public NotificationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records one terminal outcome.
     *
     * @param channel   one of {@link #CHANNEL_SMS}, {@link #CHANNEL_WHATSAPP}, {@link #CHANNEL_EMAIL}
     * @param eventType the Kafka {@code eventType} that produced the send, so a failure can be traced
     *                  back to the flow that triggered it
     * @param outcome   one of {@link #SENT}, {@link #REJECTED}, {@link #FAILED}, {@link #SKIPPED}
     */
    public void record(String channel, String eventType, String outcome) {
        Counter.builder(SEND_COUNTER)
                .tag("channel", channel)
                .tag("event_type", eventType == null ? "unknown" : eventType)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
