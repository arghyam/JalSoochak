package org.arghyam.jalsoochak.scheme.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity pin for the scheme status vocabulary.
 *
 * <p>There is no shared module between services, so {@link SchemeWorkStatus} and
 * {@link SchemeOperatingStatus} are duplicated verbatim in every service that needs them, and so is
 * this test. The tables below are pinned as literals on purpose: if one copy drifts, it fails in
 * that service alone, which is the signal. Keep the copies byte-identical apart from the package
 * declaration.
 */
@DisplayName("Scheme status vocabulary")
class SchemeStatusVocabularyTest {

    @Nested
    @DisplayName("SchemeWorkStatus")
    class WorkStatus {

        @Test
        @DisplayName("declares exactly the four documented codes, in code order")
        void declaresTheDocumentedTable() {
            assertThat(Arrays.stream(SchemeWorkStatus.values()).map(SchemeWorkStatus::getCode))
                    .containsExactly(1, 2, 3, 4);
            assertThat(Arrays.stream(SchemeWorkStatus.values()).map(SchemeWorkStatus::getLabel))
                    .containsExactly("Ongoing", "Completed", "Not Started", "Handed Over");
            assertThat(Arrays.stream(SchemeWorkStatus.values()).map(SchemeWorkStatus::getWireKey))
                    .containsExactly("ongoing", "completed", "not_started", "handed_over");
        }

        @ParameterizedTest
        @CsvSource({"1, Ongoing", "2, Completed", "3, Not Started", "4, Handed Over"})
        @DisplayName("labelOf resolves every stored code")
        void labelOfResolvesEveryCode(int code, String label) {
            assertThat(SchemeWorkStatus.labelOf(code)).isEqualTo(label);
        }

        @Test
        @DisplayName("labelOf falls back to Unknown for null and unmapped codes")
        void labelOfFallsBackToUnknown() {
            assertThat(SchemeWorkStatus.labelOf(null)).isEqualTo("Unknown");
            assertThat(SchemeWorkStatus.labelOf(99)).isEqualTo("Unknown");
        }

        @Test
        @DisplayName("fromCode is empty for null and unmapped codes")
        void fromCodeIsEmptyForUnknown() {
            assertThat(SchemeWorkStatus.fromCode(null)).isEmpty();
            assertThat(SchemeWorkStatus.fromCode(99)).isEmpty();
            assertThat(SchemeWorkStatus.fromCode(4)).contains(SchemeWorkStatus.HANDED_OVER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1", "ongoing", "Ongoing", " ONGOING "})
        @DisplayName("fromInput accepts the code and the label, ignoring case and surrounding space")
        void fromInputAcceptsCodeAndLabel(String input) {
            assertThat(SchemeWorkStatus.fromInput(input)).contains(SchemeWorkStatus.ONGOING);
        }

        @ParameterizedTest
        @ValueSource(strings = {"3", "not started", "Not Started"})
        @DisplayName("fromInput accepts multi-word labels as scheme uploads spell them")
        void fromInputAcceptsMultiWordLabels(String input) {
            assertThat(SchemeWorkStatus.fromInput(input)).contains(SchemeWorkStatus.NOT_STARTED);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "0", "5", "active", "not_started"})
        @DisplayName("fromInput rejects blank, out-of-range and unknown input")
        void fromInputRejectsUnknownInput(String input) {
            assertThat(SchemeWorkStatus.fromInput(input)).isEmpty();
        }

        @Test
        @DisplayName("acceptedInputs lists every label then every code")
        void acceptedInputsListsTheWholeTable() {
            assertThat(SchemeWorkStatus.acceptedInputs())
                    .isEqualTo("Ongoing, Completed, Not Started, Handed Over or 1/2/3/4");
        }
    }

    @Nested
    @DisplayName("SchemeOperatingStatus")
    class OperatingStatus {

        @Test
        @DisplayName("declares exactly the three documented codes, in code order")
        void declaresTheDocumentedTable() {
            assertThat(Arrays.stream(SchemeOperatingStatus.values()).map(SchemeOperatingStatus::getCode))
                    .containsExactly(0, 1, 2);
            assertThat(Arrays.stream(SchemeOperatingStatus.values()).map(SchemeOperatingStatus::getLabel))
                    .containsExactly("Non-Operative", "Operative", "Partially Operative");
            assertThat(Arrays.stream(SchemeOperatingStatus.values()).map(SchemeOperatingStatus::getWireKey))
                    .containsExactly("non_operative", "operative", "partially_operative");
        }

        @ParameterizedTest
        @CsvSource({"0, Non-Operative", "1, Operative", "2, Partially Operative"})
        @DisplayName("labelOf resolves every stored code")
        void labelOfResolvesEveryCode(int code, String label) {
            assertThat(SchemeOperatingStatus.labelOf(code)).isEqualTo(label);
        }

        @Test
        @DisplayName("labelOf falls back to Unknown for null and unmapped codes")
        void labelOfFallsBackToUnknown() {
            assertThat(SchemeOperatingStatus.labelOf(null)).isEqualTo("Unknown");
            assertThat(SchemeOperatingStatus.labelOf(99)).isEqualTo("Unknown");
        }

        @Test
        @DisplayName("fromCode is empty for null and unmapped codes")
        void fromCodeIsEmptyForUnknown() {
            assertThat(SchemeOperatingStatus.fromCode(null)).isEmpty();
            assertThat(SchemeOperatingStatus.fromCode(99)).isEmpty();
            assertThat(SchemeOperatingStatus.fromCode(0)).contains(SchemeOperatingStatus.NON_OPERATIVE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "non-operative", "Non-Operative", " NON-OPERATIVE "})
        @DisplayName("fromInput accepts the hyphenated label scheme uploads use for code 0")
        void fromInputAcceptsHyphenatedLabel(String input) {
            assertThat(SchemeOperatingStatus.fromInput(input)).contains(SchemeOperatingStatus.NON_OPERATIVE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"2", "partially operative", "Partially Operative"})
        @DisplayName("fromInput accepts multi-word labels as scheme uploads spell them")
        void fromInputAcceptsMultiWordLabels(String input) {
            assertThat(SchemeOperatingStatus.fromInput(input)).contains(SchemeOperatingStatus.PARTIALLY_OPERATIVE);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "3", "inactive", "non_operative", "nonoperative"})
        @DisplayName("fromInput rejects blank, out-of-range and unknown input")
        void fromInputRejectsUnknownInput(String input) {
            assertThat(SchemeOperatingStatus.fromInput(input)).isEmpty();
        }

        @Test
        @DisplayName("acceptedInputs lists every label then every code")
        void acceptedInputsListsTheWholeTable() {
            assertThat(SchemeOperatingStatus.acceptedInputs())
                    .isEqualTo("Non-Operative, Operative, Partially Operative or 0/1/2");
        }
    }
}
