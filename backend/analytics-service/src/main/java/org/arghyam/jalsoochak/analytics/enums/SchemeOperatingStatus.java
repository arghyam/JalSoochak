package org.arghyam.jalsoochak.analytics.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Canonical vocabulary for {@code scheme_master_table.operating_status} and
 * {@code dim_scheme_table.operating_status}.
 *
 * <p>Codes are the stored representation, labels are what every API and export returns, and
 * {@link #getWireKey()} is the stable machine-readable key for clients that must not key off a
 * display string. There are three codes, not two: this column has never been an active/inactive
 * flag.
 *
 * <p><strong>Duplicated on purpose.</strong> Services here share no Maven module, so this file is
 * copied verbatim — package declaration aside — into every service that needs it. Change one, change
 * them all; {@code SchemeStatusVocabularyTest} pins the table in each copy so drift fails a build.
 */
public enum SchemeOperatingStatus {

    NON_OPERATIVE(0, "Non-Operative"),
    OPERATIVE(1, "Operative"),
    PARTIALLY_OPERATIVE(2, "Partially Operative");

    /** Label served for a stored code that matches no known status, and for a missing one. */
    public static final String UNKNOWN_LABEL = "Unknown";

    private final int code;
    private final String label;

    SchemeOperatingStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** Stable machine-readable key, e.g. {@code partially_operative}. */
    public String getWireKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SchemeOperatingStatus> fromCode(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst();
    }

    /** Canonical label for a stored code, or {@link #UNKNOWN_LABEL} when it resolves to nothing. */
    public static String labelOf(Integer code) {
        return fromCode(code).map(SchemeOperatingStatus::getLabel).orElse(UNKNOWN_LABEL);
    }

    /**
     * Resolves user-supplied input — the numeric code or the label — ignoring case and surrounding
     * whitespace. This is the accepted spelling set for scheme uploads and status filters.
     */
    public static Optional<SchemeOperatingStatus> fromInput(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(status -> status.accepts(normalized))
                .findFirst();
    }

    private boolean accepts(String normalizedInput) {
        return normalizedInput.equals(Integer.toString(code))
                || normalizedInput.equals(label.toLowerCase(Locale.ROOT));
    }

    /** Every accepted spelling, for validation messages. */
    public static String acceptedInputs() {
        return Arrays.stream(values()).map(SchemeOperatingStatus::getLabel).collect(Collectors.joining(", "))
                + " or "
                + Arrays.stream(values()).map(status -> Integer.toString(status.code)).collect(Collectors.joining("/"));
    }
}
