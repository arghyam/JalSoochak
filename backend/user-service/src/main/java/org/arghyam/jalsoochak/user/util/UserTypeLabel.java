package org.arghyam.jalsoochak.user.util;

import java.util.Locale;

/**
 * Derives the short display label for a {@code common_schema.user_type_master_table.c_name} —
 * {@code SECTION_OFFICER} to {@code "SO"}, {@code SUB_DIVISIONAL_OFFICER} to {@code "SDO"}.
 *
 * <p>The label is computed from the {@code c_name} rather than read from a column, because
 * {@code user_type_master_table} stores no display text. Taking the initial of each
 * underscore-separated word reproduces the accepted abbreviation for every role the system defines
 * today ({@code PUMP_OPERATOR} to {@code "PO"}, {@code STATE_ADMIN} to {@code "SA"},
 * {@code SUPER_STATE_ADMIN} to {@code "SSA"}), and degrades sensibly for a role added later instead
 * of returning nothing. Single-word roles have no meaningful initialism, so they are title-cased
 * instead ({@code STAFF} to {@code "Staff"}).
 *
 * <p>If a role ever needs a label this rule cannot produce, add an explicit override here rather
 * than reshaping the rule around it.
 */
public final class UserTypeLabel {

    private UserTypeLabel() {
    }

    /**
     * @param cName the {@code c_name} value, in any casing; may be {@code null}
     * @return the short label, or {@code null} when {@code cName} is {@code null} or blank
     */
    public static String shortLabel(String cName) {
        if (cName == null || cName.isBlank()) {
            return null;
        }
        String[] words = cName.trim().split("[_\\s]+");
        if (words.length == 1) {
            String word = words[0];
            return word.substring(0, 1).toUpperCase(Locale.ROOT)
                    + word.substring(1).toLowerCase(Locale.ROOT);
        }
        StringBuilder initials = new StringBuilder(words.length);
        for (String word : words) {
            if (!word.isEmpty()) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return initials.toString();
    }
}
