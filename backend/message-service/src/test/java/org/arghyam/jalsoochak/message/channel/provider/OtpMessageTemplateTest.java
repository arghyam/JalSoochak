package org.arghyam.jalsoochak.message.channel.provider;

import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link OtpMessageTemplate} — the O2-15 rules, checked here as they are on the write
 * side in tenant-service, and the substitution itself.
 */
class OtpMessageTemplateTest {

    @Test
    void compile_defaultTemplate_rendersTodaysExactMessage() {
        // The text the singleton SmsCountrySender built with String.formatted. A tenant that sets
        // no otpTemplate, and the platform account itself, must still send exactly this.
        String rendered = OtpMessageTemplate
                .compile(SmsProviderSettings.SmsCountry.DEFAULT_OTP_TEMPLATE)
                .render("123456", 5);

        assertThat(rendered).isEqualTo(
                "Your OTP for Jalsoochak login is 123456. Do not share this OTP. Valid for 5 minutes.");
    }

    @Test
    void render_replacesBothPlaceholders() {
        String rendered = OtpMessageTemplate.compile("Code {otp}, {expiryMinutes} min").render("999", 10);

        assertThat(rendered).isEqualTo("Code 999, 10 min");
    }

    @Test
    void render_templateWithoutExpiry_leavesTheRestUntouched() {
        String rendered = OtpMessageTemplate.compile("Your code is {otp}.").render("4321", 5);

        assertThat(rendered).isEqualTo("Your code is 4321.");
    }

    @Test
    void render_isSinglePass_soASubstitutedValueIsNotRescanned() {
        // A contrived OTP, but the property matters: a two-pass implementation built on
        // String.replace would expand a placeholder that arrived in a value.
        String rendered = OtpMessageTemplate.compile("Code {otp} for {expiryMinutes} min")
                .render("{expiryMinutes}", 7);

        assertThat(rendered).isEqualTo("Code {expiryMinutes} for 7 min");
    }

    @Test
    void render_valueWithRegexReplacementSyntax_isTakenLiterally() {
        // $ and \\ are group-reference syntax to Matcher.appendReplacement; unquoted they would
        // throw or silently splice part of the template into the message.
        String rendered = OtpMessageTemplate.compile("Code {otp}").render("$1\\x", 5);

        assertThat(rendered).isEqualTo("Code $1\\x");
    }

    @Test
    void compile_trimsSurroundingWhitespace() {
        assertThat(OtpMessageTemplate.compile("  Code {otp}  ").render("1", 5)).isEqualTo("Code 1");
    }

    @Test
    void compile_missingOtpPlaceholder_isRefused() {
        assertThatThrownBy(() -> OtpMessageTemplate.compile("Your code is on its way."))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("exactly once, found 0");
    }

    @Test
    void compile_repeatedOtpPlaceholder_isRefused() {
        assertThatThrownBy(() -> OtpMessageTemplate.compile("{otp} — again, {otp}"))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("exactly once, found 2");
    }

    @Test
    void compile_repeatedExpiryPlaceholder_isRefused() {
        assertThatThrownBy(() ->
                OtpMessageTemplate.compile("{otp} valid {expiryMinutes}/{expiryMinutes} min"))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("at most once, found 2");
    }

    @Test
    void compile_unknownPlaceholder_isRefused() {
        assertThatThrownBy(() -> OtpMessageTemplate.compile("{otp} from {stateName}"))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("{stateName}");
    }

    @Test
    void compile_blankOrNull_isRefused() {
        assertThatThrownBy(() -> OtpMessageTemplate.compile(null))
                .isInstanceOf(ProviderNotUsableException.class);
        assertThatThrownBy(() -> OtpMessageTemplate.compile("   "))
                .isInstanceOf(ProviderNotUsableException.class);
    }

    @Test
    void compile_overLengthTemplate_isRefused() {
        String tooLong = "{otp} " + "x".repeat(OtpMessageTemplate.MAX_LENGTH);

        assertThatThrownBy(() -> OtpMessageTemplate.compile(tooLong))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("exceeds the maximum");
    }

    @Test
    void compile_templateAtExactlyTheLimit_isAccepted() {
        String atLimit = "{otp}" + "x".repeat(OtpMessageTemplate.MAX_LENGTH - "{otp}".length());

        assertThat(OtpMessageTemplate.compile(atLimit).render("1", 5)).startsWith("1x");
    }

    @Test
    void toString_isTheTemplateAndNeverARenderedMessage() {
        // A rendered message carries a live OTP; the template does not.
        assertThat(OtpMessageTemplate.compile("Code {otp}")).hasToString("Code {otp}");
    }
}
