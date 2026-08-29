package org.arghyam.jalsoochak.message.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link NotificationMetrics}.
 *
 * <p>This counter exists because the OTP channels had no instrumentation at all: {@code otp-topic}
 * runs {@code FixedBackOff(0, 0)} and both OTP branches swallow their own failures by design, so a
 * failed login OTP reached no recoverer and incremented no dead-letter counter. An SMSCountry outage
 * was invisible to monitoring.
 */
class NotificationMetricsTest {

    @Test
    void record_incrementsPerChannelEventTypeAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry);

        metrics.record(NotificationMetrics.CHANNEL_SMS, "SEND_LOGIN_OTP", NotificationMetrics.FAILED);
        metrics.record(NotificationMetrics.CHANNEL_SMS, "SEND_LOGIN_OTP", NotificationMetrics.FAILED);
        metrics.record(NotificationMetrics.CHANNEL_SMS, "SEND_LOGIN_OTP", NotificationMetrics.SENT);

        assertThat(count(registry, "SMS", "SEND_LOGIN_OTP", NotificationMetrics.FAILED)).isEqualTo(2.0);
        assertThat(count(registry, "SMS", "SEND_LOGIN_OTP", NotificationMetrics.SENT)).isEqualTo(1.0);
    }

    /** Outcomes must stay separable per channel, or an email outage would mask an OTP one. */
    @Test
    void record_keepsChannelsIndependent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry);

        metrics.record(NotificationMetrics.CHANNEL_SMS, "SEND_LOGIN_OTP", NotificationMetrics.FAILED);
        metrics.record(NotificationMetrics.CHANNEL_EMAIL, "SEND_INVITE_EMAIL", NotificationMetrics.FAILED);

        assertThat(count(registry, "SMS", "SEND_LOGIN_OTP", NotificationMetrics.FAILED)).isEqualTo(1.0);
        assertThat(count(registry, "EMAIL", "SEND_INVITE_EMAIL", NotificationMetrics.FAILED)).isEqualTo(1.0);
    }

    /**
     * Prometheus requires every sample of a metric to carry the same label keys, and Micrometer
     * enforces that at registration: a call site that omitted one would throw on whichever path ran
     * second — silently disarming the alerts this counter feeds rather than merely degrading them.
     * {@link SimpleMeterRegistry} does not enforce it, hence the real registry here.
     */
    @Test
    void record_usesOneTagKeySetAcrossEveryChannelAndOutcome() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        NotificationMetrics metrics = new NotificationMetrics(prometheus);

        assertThatCode(() -> {
            metrics.record(NotificationMetrics.CHANNEL_SMS, "SEND_LOGIN_OTP", NotificationMetrics.SENT);
            metrics.record(NotificationMetrics.CHANNEL_WHATSAPP, "SEND_LOGIN_OTP", NotificationMetrics.FAILED);
            metrics.record(NotificationMetrics.CHANNEL_EMAIL, "SEND_INVITE_EMAIL", NotificationMetrics.REJECTED);
            metrics.record(NotificationMetrics.CHANNEL_EMAIL, "SEND_REINVITE_EMAIL", NotificationMetrics.SKIPPED);
        }).doesNotThrowAnyException();

        assertThat(prometheus.find(NotificationMetrics.SEND_COUNTER).counters())
                .hasSize(4)
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .extracting(Tag::getKey)
                        .containsExactlyInAnyOrder("channel", "event_type", "outcome"));
    }

    /** A null event type must not produce a counter with a missing label. */
    @Test
    void record_labelsAnUnknownEventTypeRatherThanOmittingIt() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        new NotificationMetrics(prometheus)
                .record(NotificationMetrics.CHANNEL_SMS, null, NotificationMetrics.FAILED);

        assertThat(prometheus.find(NotificationMetrics.SEND_COUNTER).tag("event_type", "unknown").counter())
                .isNotNull();
    }

    private static double count(SimpleMeterRegistry registry, String channel, String eventType, String outcome) {
        var counter = registry.find(NotificationMetrics.SEND_COUNTER)
                .tag("channel", channel).tag("event_type", eventType).tag("outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
