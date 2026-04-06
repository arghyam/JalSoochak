package org.arghyam.jalsoochak.analytics.constant;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Mirrors AnomalyConstants in telemetry-service. Integer codes must stay in sync. */
public enum EscalationType {
    UNREADABLE_IMAGE(1, "UNREADABLE_IMAGE"),
    MANUAL_OVERRIDE(2, "MANUAL_OVERRIDE"),
    CONSECUTIVE_OVERRIDE_5_DAYS(3, "CONSECUTIVE_OVERRIDE_5_DAYS"),
    DUPLICATE_IMAGE_SUBMISSION(4, "DUPLICATE_IMAGE_SUBMISSION"),
    READING_LESS_THAN_PREVIOUS(5, "READING_LESS_THAN_PREVIOUS"),
    NO_WATER_SUPPLY(6, "NO_WATER_SUPPLY"),
    LOW_WATER_SUPPLY(7, "LOW_WATER_SUPPLY"),
    OVER_WATER_SUPPLY(8, "OVER_WATER_SUPPLY"),
    NO_SUBMISSION(9, "NO_SUBMISSION");

    public final int code;
    public final String label;

    EscalationType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public String dbType() {
        return switch (this) {
            case NO_WATER_SUPPLY -> "no_supply";
            case LOW_WATER_SUPPLY -> "under_supply";
            case OVER_WATER_SUPPLY -> "over_supply";
            case NO_SUBMISSION -> "no_submission";
            default -> label.toLowerCase(Locale.ROOT);
        };
    }

    public static EscalationType fromCode(Integer code) {
        if (code == null) return null;
        for (EscalationType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }

    public static String toDbType(Integer code) {
        EscalationType type = fromCode(code);
        return type != null ? type.dbType() : null;
    }

    public static String normalizeDbType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return switch (normalized) {
            case "no_water_supply" -> "no_supply";
            case "low_water_supply" -> "under_supply";
            case "over_water_supply" -> "over_supply";
            default -> normalized;
        };
    }

    /** Anomalies scoped to a water supply event — correlationId keyed on (tenantId, schemeId, type). */
    public static final Set<EscalationType> WATER_ANOMALIES = EnumSet.of(
            NO_WATER_SUPPLY, LOW_WATER_SUPPLY, OVER_WATER_SUPPLY);

    /** Anomalies scoped to a specific user action — correlationId keyed on (userId, tenantId, schemeId, type). */
    public static final Set<EscalationType> USER_ANOMALIES = EnumSet.of(
            UNREADABLE_IMAGE, MANUAL_OVERRIDE, CONSECUTIVE_OVERRIDE_5_DAYS,
            DUPLICATE_IMAGE_SUBMISSION, READING_LESS_THAN_PREVIOUS, NO_SUBMISSION);
}
