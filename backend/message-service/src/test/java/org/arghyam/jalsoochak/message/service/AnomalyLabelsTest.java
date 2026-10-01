package org.arghyam.jalsoochak.message.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both forms of {@code anomaly_table.type} still sit in the table — the enum name, and the numeric
 * code as a string on rows written before analytics migration V29 — so both have to resolve to the
 * same wording on the report.
 */
class AnomalyLabelsTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "UNREADABLE_IMAGE,Unreadable Image",
            "DUPLICATE_IMAGE_SUBMISSION,Duplicate Image",
            "READING_LESS_THAN_PREVIOUS,Reading Less Than Previous",
            "NO_WATER_SUPPLY,No Water Supply",
            "NO_SUBMISSION,No Submission",
            "IMPLAUSIBLE_WATER_SUPPLY,Implausible Water Supply",
            "LOCATION_MISMATCH,Location Mismatch"
    })
    void mapsTheEnumName(String type, String expected) {
        assertThat(AnomalyLabels.label(type)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "legacy code {0} → {1}")
    @CsvSource({
            "1,Unreadable Image",
            "4,Duplicate Image",
            "5,Reading Less Than Previous",
            "6,No Water Supply",
            "9,No Submission",
            "10,Implausible Water Supply",
            "11,Location Mismatch"
    })
    void mapsTheLegacyNumericCode(String code, String expected) {
        assertThat(AnomalyLabels.label(code)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an unknown type degrades to readable English rather than shouting the constant")
    void prettifiesAnUnknownType() {
        // The anomaly section is data-driven, so a type introduced upstream reaches the report before
        // anyone adds it here. Printing SOME_NEW_TYPE at an officer is worse than guessing the words.
        assertThat(AnomalyLabels.label("SOME_NEW_TYPE")).isEqualTo("Some New Type");
    }

    @Test
    void toleratesPaddingAndCase() {
        assertThat(AnomalyLabels.label("  no_water_supply  ")).isEqualTo("No Water Supply");
    }

    @Test
    void returnsBlankForNoTypeAtAll() {
        assertThat(AnomalyLabels.label(null)).isEmpty();
        assertThat(AnomalyLabels.label("  ")).isEmpty();
    }
}
