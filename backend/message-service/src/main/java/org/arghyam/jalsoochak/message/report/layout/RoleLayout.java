package org.arghyam.jalsoochak.message.report.layout;

/**
 * The layout one role gets: either the built-in one for the (report, role) pair, or a complete
 * custom layout. Never a merge of the two. tenant-service guarantees exactly one is set.
 */
public record RoleLayout(boolean useDefault, ReportLayout layout) {
}
