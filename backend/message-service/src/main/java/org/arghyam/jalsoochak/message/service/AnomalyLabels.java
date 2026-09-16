package org.arghyam.jalsoochak.message.service;

import java.util.Locale;
import java.util.Map;

/**
 * Turns a stored anomaly type into the wording an officer reads.
 *
 * <p>{@code anomaly_table.type} holds the enum NAME, but rows written before analytics migration V29
 * hold the numeric code as a string, and both still sit in the table. Reports render whatever types
 * actually occurred, so both forms have to resolve — and an unrecognised one has to degrade to
 * something readable rather than printing {@code CONSECUTIVE_OVERRIDE_5_DAYS} at an officer or, worse,
 * dropping the row.</p>
 */
public final class AnomalyLabels {

    /** Enum NAME → the label used in the report. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("UNREADABLE_IMAGE", "Unreadable Image"),
            Map.entry("MANUAL_OVERRIDE", "Manual Override"),
            Map.entry("CONSECUTIVE_OVERRIDE_5_DAYS", "Consecutive Override (5 days)"),
            Map.entry("DUPLICATE_IMAGE_SUBMISSION", "Duplicate Image"),
            Map.entry("READING_LESS_THAN_PREVIOUS", "Reading Less Than Previous"),
            Map.entry("NO_WATER_SUPPLY", "No Water Supply"),
            Map.entry("LOW_WATER_SUPPLY", "Low Water Supply"),
            Map.entry("OVER_WATER_SUPPLY", "Over Water Supply"),
            Map.entry("NO_SUBMISSION", "No Submission"),
            Map.entry("IMPLAUSIBLE_WATER_SUPPLY", "Implausible Water Supply"));

    /** Legacy pre-V29 rows store the numeric code as a string. */
    private static final Map<String, String> CODE_TO_NAME = Map.ofEntries(
            Map.entry("1", "UNREADABLE_IMAGE"), Map.entry("2", "MANUAL_OVERRIDE"),
            Map.entry("3", "CONSECUTIVE_OVERRIDE_5_DAYS"), Map.entry("4", "DUPLICATE_IMAGE_SUBMISSION"),
            Map.entry("5", "READING_LESS_THAN_PREVIOUS"), Map.entry("6", "NO_WATER_SUPPLY"),
            Map.entry("7", "LOW_WATER_SUPPLY"), Map.entry("8", "OVER_WATER_SUPPLY"),
            Map.entry("9", "NO_SUBMISSION"), Map.entry("10", "IMPLAUSIBLE_WATER_SUPPLY"));

    private AnomalyLabels() {
    }

    public static String label(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String key = type.trim();
        String name = CODE_TO_NAME.getOrDefault(key, key);
        String label = LABELS.get(name.toUpperCase(Locale.ROOT));
        return label != null ? label : prettify(name);
    }

    /** {@code SOME_NEW_TYPE} → {@code Some New Type}, so a type added upstream still reads as English. */
    private static String prettify(String raw) {
        String[] words = raw.replace('_', ' ').trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder(raw.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
