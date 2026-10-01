package org.arghyam.jalsoochak.telemetry.repository;

import java.time.LocalDateTime;

/**
 * A {@code flow_reading_table} row's identity and the version a write just gave it.
 *
 * <p>{@code updatedAt} is the row's {@code updated_at} as the database wrote it, returned by the same
 * statement that stored the reading. It travels on {@code METER_READING_RECORDED} as
 * {@code sourceUpdatedAt}, and analytics keeps the newest version of each submission. It is never
 * the Java clock: two writers' clocks don't order their commits, the row lock does.
 *
 * @param updatedAt {@code null} only when the row vanished before the write reached it
 */
public record FlowReadingVersion(Long id, LocalDateTime updatedAt) {
}
