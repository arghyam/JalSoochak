package org.arghyam.jalsoochak.message.service;

import java.time.LocalDate;

/**
 * Filenames and MinIO object keys for the water reports.
 *
 * <p>Held in one place because the two halves have to agree: the PDF is written to disk under the
 * filename, uploaded under the object key, and the key's tail becomes the WhatsApp button's URL
 * variable. A mismatch produces a link the officer taps and nothing happens — a failure invisible on
 * our side, since the upload and the send both succeed.</p>
 *
 * <p>Layout, one folder per role and one per period:</p>
 * <pre>
 * daily-water-reports/SO/2026-07-19/daily_water_report_SECTION_OFFICER_21343_2026-07-19.pdf
 * weekly-water-reports/SO/2026-07-13_to_2026-07-19/weekly_water_report_SECTION_OFFICER_21343_2026-07-13_to_2026-07-19.pdf
 * weekly-water-reports/SDO/2026-07-13_to_2026-07-19/weekly_water_report_SUB_DIVISIONAL_OFFICER_5521_2026-07-13_to_2026-07-19.pdf
 * </pre>
 *
 * <p>{@code officerUserId} is the discriminator rather than the officer's name: names collide, the
 * buckets are shared across every officer in every tenant, and a collision would overwrite one
 * officer's report with another's — handing the second officer the first one's scheme list and Jal
 * Mitra phone numbers.</p>
 */
public final class ReportFileNaming {

    public static final String DAILY_BUCKET = "daily-water-reports";
    public static final String WEEKLY_BUCKET = "weekly-water-reports";

    private static final String SO_FOLDER = "SO";
    private static final String SDO_FOLDER = "SDO";
    private static final String SDO_ROLE = "SUB_DIVISIONAL_OFFICER";

    private ReportFileNaming() {
    }

    public static String dailyFilename(String officerUserType, long officerUserId, LocalDate reportDate) {
        return String.format("daily_water_report_%s_%d_%s.pdf", safeRole(officerUserType), officerUserId, reportDate);
    }

    public static String weeklyFilename(String officerUserType, long officerUserId,
                                        LocalDate weekStart, LocalDate weekEnd) {
        return String.format("weekly_water_report_%s_%d_%s.pdf",
                safeRole(officerUserType), officerUserId, weekRange(weekStart, weekEnd));
    }

    /** {@code SO/2026-07-19/<filename>} — the object key within {@link #DAILY_BUCKET}. */
    public static String dailyObjectKey(String officerUserType, String filename, LocalDate reportDate) {
        return roleFolder(officerUserType) + "/" + reportDate + "/" + filename;
    }

    /** {@code SDO/2026-07-13_to_2026-07-19/<filename>} — the object key within {@link #WEEKLY_BUCKET}. */
    public static String weeklyObjectKey(String officerUserType, String filename,
                                         LocalDate weekStart, LocalDate weekEnd) {
        return roleFolder(officerUserType) + "/" + weekRange(weekStart, weekEnd) + "/" + filename;
    }

    /** {@code 2026-07-13_to_2026-07-19} — used identically in the folder and the filename. */
    public static String weekRange(LocalDate weekStart, LocalDate weekEnd) {
        return weekStart + "_to_" + weekEnd;
    }

    /** {@code SO} or {@code SDO} — the short folder name, not the full role token. */
    private static String roleFolder(String officerUserType) {
        return isSdo(officerUserType) ? SDO_FOLDER : SO_FOLDER;
    }

    /**
     * The role as a filename-safe token. Anything outside {@code [A-Za-z0-9_-]} becomes an underscore
     * so a malformed role can never produce a path separator or escape the folder.
     */
    private static String safeRole(String officerUserType) {
        String role = officerUserType != null ? officerUserType : "OFFICER";
        return role.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static boolean isSdo(String officerUserType) {
        return officerUserType != null && SDO_ROLE.equalsIgnoreCase(officerUserType.trim());
    }
}
