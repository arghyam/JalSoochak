package org.arghyam.jalsoochak.tenant.enums;

/**
 * The per-tenant notification jobs run by {@code NotificationJobScheduler}. The name is stored in
 * {@code common_schema.scheduled_job_run_table.job_type}, so renaming a constant orphans its rows.
 */
public enum ScheduledJobType {
    NUDGE,
    ESCALATION,
    DAILY_REPORT,
    WEEKLY_REPORT
}
