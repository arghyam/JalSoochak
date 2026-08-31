package org.arghyam.jalsoochak.message.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PhoneRedactor}.
 *
 * <p>Numbers here use the {@code 91XXXXXXXXXX} shape but are not real, per CLAUDE.md.</p>
 */
class PhoneRedactorTest {

    @Test
    void masksAllButTheLastFourDigits() {
        assertThat(PhoneRedactor.redact("calling 919999900001 now")).isEqualTo("calling ********0001 now");
    }

    @Test
    void masksABareTenDigitMobile() {
        assertThat(PhoneRedactor.redact("9999900001")).isEqualTo("******0001");
    }

    @Test
    void masksEveryOccurrence() {
        assertThat(PhoneRedactor.redact("919999900001 and 919999900002"))
                .doesNotContain("919999900001")
                .doesNotContain("919999900002")
                .contains("0001")
                .contains("0002");
    }

    /**
     * The real reason this exists: Gupshup's failure payload puts the recipient's number in
     * {@code destination}, so anything derived from that payload must come out masked.
     */
    @Test
    void masksTheDestinationInsideABspFailurePayload() {
        String payload = """
                {"payload":{"payload":{"reason":"Message undeliverable","code":131026},\
                "destination":"919999900001"}}""";

        assertThat(PhoneRedactor.redact(payload))
                .doesNotContain("919999900001")
                .contains("\"code\":131026");
    }

    @Test
    void leavesShortNumbersAlone() {
        assertThat(PhoneRedactor.redact("code 131026 and id 42")).isEqualTo("code 131026 and id 42");
    }

    @Test
    void returnsNullForNull() {
        assertThat(PhoneRedactor.redact(null)).isNull();
    }
}
