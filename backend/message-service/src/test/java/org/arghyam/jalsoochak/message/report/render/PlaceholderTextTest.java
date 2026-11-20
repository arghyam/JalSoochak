package org.arghyam.jalsoochak.message.report.render;

import org.arghyam.jalsoochak.message.report.catalog.Placeholder;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderTextTest {

    private final Map<Placeholder, String> values = new EnumMap<>(Map.of(
            Placeholder.OFFICER_NAME, "Binod Nimoli",
            Placeholder.CUTOFF, "16:00"));

    private String resolve(String text) {
        return PlaceholderText.resolve(text, values::get);
    }

    @Test
    void replacesEveryPlaceholderWithItsValue() {
        assertThat(resolve("Officer: {officerName}, up to {cutoff} ({cutoff})"))
                .isEqualTo("Officer: Binod Nimoli, up to 16:00 (16:00)");
    }

    @Test
    void printsNothingForAPlaceholderWithNoValue() {
        assertThat(resolve("[{reportDate}]")).isEqualTo("[]");
    }

    @Test
    void leavesANameTheCatalogueDoesNotKnowAsWritten() {
        assertThat(resolve("{officerNam} at {cutoff}")).isEqualTo("{officerNam} at 16:00");
    }

    @Test
    void leavesMalformedBracesAlone() {
        assertThat(resolve("{ officerName} {officerName")).isEqualTo("{ officerName} {officerName");
    }

    @Test
    void insertsAValueLiterallyEvenWithRegexReplacementCharacters() {
        values.put(Placeholder.OFFICER_NAME, "A$1 \\B");

        assertThat(resolve("{officerName}")).isEqualTo("A$1 \\B");
    }

    @Test
    void treatsNullTextAsEmpty() {
        assertThat(resolve(null)).isEmpty();
    }
}
