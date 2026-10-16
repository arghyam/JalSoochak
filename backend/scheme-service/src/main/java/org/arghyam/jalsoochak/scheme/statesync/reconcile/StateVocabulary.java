package org.arghyam.jalsoochak.scheme.statesync.reconcile;

import org.arghyam.jalsoochak.scheme.enums.SchemeOperatingStatus;
import org.arghyam.jalsoochak.scheme.enums.SchemeWorkStatus;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Upstream (JJM Brain) vocabulary → ours. The status and role vocabularies are closed: a value
 * outside them (a numeric code included) is returned as empty rather than guessed at; the caller decides whether that blocks a write or keeps what the
 * database already holds.
 */
public final class StateVocabulary {

    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern COMBINING = Pattern.compile("\\p{M}+");

    private static final Map<String, SchemeWorkStatus> WORK_STATUS = Map.of(
            "ongoing", SchemeWorkStatus.ONGOING,
            "completed", SchemeWorkStatus.COMPLETED,
            "not-started", SchemeWorkStatus.NOT_STARTED,
            "handed-over", SchemeWorkStatus.HANDED_OVER);

    private static final Map<String, SchemeOperatingStatus> OPERATING_STATUS = Map.of(
            "operative", SchemeOperatingStatus.OPERATIVE,
            "non-operative", SchemeOperatingStatus.NON_OPERATIVE,
            "partially-operative", SchemeOperatingStatus.PARTIALLY_OPERATIVE);

    /**
     * Allow-list of upstream roles we onboard, to {@code user_type_master_table.c_name}. The same four
     * the spreadsheet ingest takes; khalasi, jal-sahayak and "other" are deliberately absent.
     */
    private static final Map<String, String> ROLES = Map.of(
            "jal-mitra", "PUMP_OPERATOR",
            "jalmitra", "PUMP_OPERATOR",
            "pump-operator", "PUMP_OPERATOR",
            "section-officer", "SECTION_OFFICER",
            "sdo", "SUB_DIVISIONAL_OFFICER",
            "executive-engineer", "EXECUTIVE_ENGINEER");

    /** The user types this sync owns. Mappings of any other user type are never touched. */
    public static final List<String> MANAGED_USER_TYPES =
            List.of("PUMP_OPERATOR", "SECTION_OFFICER", "SUB_DIVISIONAL_OFFICER", "EXECUTIVE_ENGINEER");

    private StateVocabulary() {
    }

    public static Optional<SchemeWorkStatus> workStatus(String value) {
        String key = slug(value);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(WORK_STATUS.get(key));
    }

    public static Optional<SchemeOperatingStatus> operatingStatus(String value) {
        String key = slug(value);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(OPERATING_STATUS.get(key));
    }

    /** Our user type name for an upstream role, or empty when the role is outside the allow-list. */
    public static Optional<String> userType(String role) {
        String key = slug(role);
        return key == null ? Optional.empty() : Optional.ofNullable(ROLES.get(key));
    }

    /**
     * The {@code 91XXXXXXXXXX} form user_table stores (and hashes). Accepts a bare 10-digit mobile,
     * a {@code +91} / {@code 91} prefix and a trunk {@code 0}; anything else is rejected.
     */
    public static Optional<String> phone(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return INDIAN_MOBILE.matcher(digits).matches() ? Optional.of("91" + digits) : Optional.empty();
    }

    public static Optional<Double> coordinate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) && parsed != 0.0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Name comparison key: accents folded, lower-cased, punctuation collapsed to single spaces. */
    public static String nameKey(String value) {
        if (value == null) {
            return "";
        }
        String folded = COMBINING.matcher(Normalizer.normalize(value, Normalizer.Form.NFKD)).replaceAll("");
        return NON_ALNUM.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /**
     * {@link #nameKey} with trailing level words dropped, so "Bajali Division" meets "Bajali" and
     * "Amguri Sub-Division" meets "Amguri".
     */
    public static String nameKeyWithoutSuffix(String value, List<String> suffixes) {
        String key = nameKey(value);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String suffix : suffixes) {
                if (key.endsWith(" " + suffix)) {
                    key = key.substring(0, key.length() - suffix.length() - 1).trim();
                    stripped = true;
                }
            }
        }
        return key;
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
    }
}
