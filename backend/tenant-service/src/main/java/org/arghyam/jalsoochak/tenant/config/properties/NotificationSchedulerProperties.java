package org.arghyam.jalsoochak.tenant.config.properties;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Settings for {@code NotificationJobScheduler}, the per-minute tick that runs each tenant's nudge,
 * escalation, daily report and weekly report. Bound from {@code notification-scheduler.*}.
 */
@ConfigurationProperties(prefix = "notification-scheduler")
@Validated
@Getter
@Setter
public class NotificationSchedulerProperties {

    /** Kill switch. {@code false} stops every notification job on this pod. */
    private boolean enabled = true;

    /**
     * How long after its slot a job may still start. Covers a restart or a rolling deploy that
     * spans the slot; a slot missed by more than this is skipped for the day. Under 24 hours, since
     * a slot is always looked for on the current IST day.
     */
    @NotNull
    @DurationMin(nanos = 0)
    @DurationMax(hours = 24, inclusive = false)
    private Duration grace = Duration.ofMinutes(15);
}
