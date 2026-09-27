package org.arghyam.jalsoochak.message.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the filenames and object keys the water reports are stored under.
 *
 * <p>These are asserted literally because three separate things have to agree on them: the file
 * written to disk, the storage object uploaded, and the suffix Meta appends to the approved WhatsApp
 * template's frozen URL prefix. A drift between them produces a button the officer taps to nothing —
 * a failure invisible on our side, because both the upload and the send succeed.</p>
 */
class ReportFileNamingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 19);
    private static final LocalDate WEEK_START = LocalDate.of(2026, 7, 13);
    private static final LocalDate WEEK_END = LocalDate.of(2026, 7, 19);

    @Nested
    @DisplayName("daily")
    class Daily {

        @Test
        void filenameCarriesRoleOfficerAndDate() {
            assertThat(ReportFileNaming.dailyFilename("SECTION_OFFICER", 21343L, DAY))
                    .isEqualTo("daily_water_report_SECTION_OFFICER_21343_2026-07-19.pdf");
        }

        @Test
        void objectKeyNestsByRoleThenDate() {
            String filename = ReportFileNaming.dailyFilename("SECTION_OFFICER", 21343L, DAY);

            assertThat(ReportFileNaming.dailyObjectKey("SECTION_OFFICER", filename, DAY))
                    .isEqualTo("SO/2026-07-19/daily_water_report_SECTION_OFFICER_21343_2026-07-19.pdf");
        }
    }

    @Nested
    @DisplayName("weekly")
    class Weekly {

        @Test
        void filenameCarriesTheFullWeekRange() {
            assertThat(ReportFileNaming.weeklyFilename("SECTION_OFFICER", 21343L, WEEK_START, WEEK_END))
                    .isEqualTo("weekly_water_report_SECTION_OFFICER_21343_2026-07-13_to_2026-07-19.pdf");
        }

        @Test
        void objectKeyNestsByRoleThenWeekRange() {
            String filename = ReportFileNaming.weeklyFilename("SUB_DIVISIONAL_OFFICER", 5521L, WEEK_START, WEEK_END);

            assertThat(ReportFileNaming.weeklyObjectKey("SUB_DIVISIONAL_OFFICER", filename, WEEK_START, WEEK_END))
                    .isEqualTo("SDO/2026-07-13_to_2026-07-19/"
                            + "weekly_water_report_SUB_DIVISIONAL_OFFICER_5521_2026-07-13_to_2026-07-19.pdf");
        }

        @Test
        void theFolderAndTheFilenameUseTheSameRange() {
            // They are built separately; if they ever disagreed the key would still be valid and the
            // mismatch would only show up to someone browsing the bucket.
            String filename = ReportFileNaming.weeklyFilename("SECTION_OFFICER", 1L, WEEK_START, WEEK_END);
            String key = ReportFileNaming.weeklyObjectKey("SECTION_OFFICER", filename, WEEK_START, WEEK_END);

            assertThat(key).contains(ReportFileNaming.weekRange(WEEK_START, WEEK_END) + "/");
            assertThat(filename).contains(ReportFileNaming.weekRange(WEEK_START, WEEK_END));
        }
    }

    @Nested
    @DisplayName("role handling")
    class Roles {

        @Test
        void anySoRoleLandsInTheSoFolder() {
            String filename = ReportFileNaming.dailyFilename("SECTION_OFFICER", 1L, DAY);

            assertThat(ReportFileNaming.dailyObjectKey("SECTION_OFFICER", filename, DAY)).startsWith("SO/");
        }

        @Test
        void theSdoRoleIsMatchedRegardlessOfCaseAndPadding() {
            String filename = ReportFileNaming.weeklyFilename(" sub_divisional_officer ", 1L, WEEK_START, WEEK_END);

            assertThat(ReportFileNaming.weeklyObjectKey(" sub_divisional_officer ", filename,
                    WEEK_START, WEEK_END)).startsWith("SDO/");
        }

        @Test
        void anUnknownRoleFallsBackToTheSoFolderRatherThanCreatingAStrayOne() {
            String filename = ReportFileNaming.dailyFilename("INSPECTOR", 1L, DAY);

            assertThat(ReportFileNaming.dailyObjectKey("INSPECTOR", filename, DAY)).startsWith("SO/");
        }

        @Test
        void aNullRoleStillProducesAUsableName() {
            assertThat(ReportFileNaming.dailyFilename(null, 1L, DAY))
                    .isEqualTo("daily_water_report_OFFICER_1_2026-07-19.pdf");
        }

        @Test
        void aRoleCannotInjectAPathSeparatorIntoTheKey() {
            // The role reaches here from a Kafka event. A slash in it would move the object to a
            // different folder — or out of the role folder entirely.
            String filename = ReportFileNaming.dailyFilename("../../etc/passwd", 1L, DAY);

            assertThat(filename).doesNotContain("/");
            assertThat(filename).doesNotContain("..");
            assertThat(filename).isEqualTo("daily_water_report_______etc_passwd_1_2026-07-19.pdf");
        }
    }
}
