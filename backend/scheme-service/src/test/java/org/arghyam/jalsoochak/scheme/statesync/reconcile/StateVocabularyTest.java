package org.arghyam.jalsoochak.scheme.statesync.reconcile;

import org.arghyam.jalsoochak.scheme.enums.SchemeOperatingStatus;
import org.arghyam.jalsoochak.scheme.enums.SchemeWorkStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StateVocabularyTest {

    @ParameterizedTest
    @CsvSource({"ongoing,ONGOING", "completed,COMPLETED", "not-started,NOT_STARTED", "handed-over,HANDED_OVER",
            "Handed Over,HANDED_OVER", "NOT_STARTED,NOT_STARTED"})
    void mapsTheDocumentedWorkStatuses(String upstream, SchemeWorkStatus expected) {
        assertThat(StateVocabulary.workStatus(upstream)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({"operative,OPERATIVE", "non-operative,NON_OPERATIVE", "partially-operative,PARTIALLY_OPERATIVE"})
    void mapsTheDocumentedOperatingStatuses(String upstream, SchemeOperatingStatus expected) {
        assertThat(StateVocabulary.operatingStatus(upstream)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "0", "abandoned", " "})
    void neverGuessesAStatus(String upstream) {
        assertThat(StateVocabulary.workStatus(upstream)).isEmpty();
        assertThat(StateVocabulary.operatingStatus(upstream)).isEmpty();
    }

    @Test
    void onboardsOnlyTheFourAllowListedRoles() {
        assertThat(StateVocabulary.userType("jal-mitra")).contains("PUMP_OPERATOR");
        assertThat(StateVocabulary.userType("section-officer")).contains("SECTION_OFFICER");
        assertThat(StateVocabulary.userType("sdo")).contains("SUB_DIVISIONAL_OFFICER");
        assertThat(StateVocabulary.userType("executive-engineer")).contains("EXECUTIVE_ENGINEER");
        assertThat(StateVocabulary.userType("khalasi")).isEmpty();
        assertThat(StateVocabulary.userType("jal-sahayak")).isEmpty();
        assertThat(StateVocabulary.userType("other")).isEmpty();
        assertThat(StateVocabulary.userType(null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"9100000001,919100000001", "+91 91000 00001,919100000001", "919100000001,919100000001",
            "09100000001,919100000001"})
    void normalisesAMobileToTheStoredForm(String upstream, String expected) {
        assertThat(StateVocabulary.phone(upstream)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234123490", "12345", "0361-2345678", ""})
    void rejectsAnythingButAnIndianMobile(String upstream) {
        assertThat(StateVocabulary.phone(upstream)).isEmpty();
    }

    @Test
    void readsCoordinatesAndDropsZeroOrJunk() {
        assertThat(StateVocabulary.coordinate("27.15429600")).contains(27.154296);
        assertThat(StateVocabulary.coordinate("0.0")).isEmpty();
        assertThat(StateVocabulary.coordinate("n/a")).isEmpty();
    }

    @Test
    void comparesNamesWithoutCasePunctuationOrLevelSuffix() {
        assertThat(StateVocabulary.nameKey("NIZ-SARIHA (NIZASARIHA)")).isEqualTo("niz sariha nizasariha");
        assertThat(StateVocabulary.nameKeyWithoutSuffix("Bajali Division", List.of("division", "div")))
                .isEqualTo(StateVocabulary.nameKeyWithoutSuffix("BAJALI", List.of("division", "div")));
        assertThat(StateVocabulary.nameKeyWithoutSuffix("Amguri Sub-Division", List.of("sub division")))
                .isEqualTo("amguri");
    }
}
