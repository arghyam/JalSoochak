package org.arghyam.jalsoochak.tenant.dto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MESSAGING-PROVIDER-SETTINGS: the allowlist is the only thing standing between a state admin's
 * SMTP settings and an arbitrary destination for that state's SMTP password, so both halves are
 * tested — what it accepts as a pattern, and what a pattern then matches.
 */
@DisplayName("MessagingAllowedHostsConfigDTO Tests")
class MessagingAllowedHostsConfigDTOTest {

    private static MessagingAllowedHostsConfigDTO of(String... patterns) {
        return MessagingAllowedHostsConfigDTO.builder().smtp(List.of(patterns)).build();
    }

    @Nested
    @DisplayName("validatedSmtpHosts")
    class Validation {

        @Test
        @DisplayName("Normalises patterns to lower case")
        void normalisesToLowerCase() {
            assertThat(of("SMTP.MP.GOV.IN", " *.NIC.IN ").validatedSmtpHosts())
                    .containsExactly("smtp.mp.gov.in", "*.nic.in");
        }

        @Test
        @DisplayName("An empty list is valid and allows nothing")
        void emptyListIsValid() {
            MessagingAllowedHostsConfigDTO empty = MessagingAllowedHostsConfigDTO.builder()
                    .smtp(List.of()).build();

            assertThat(empty.validatedSmtpHosts()).isEmpty();
            assertThat(empty.allowsSmtpHost("smtp.mp.gov.in")).isFalse();
        }

        @Test
        @DisplayName("A null smtp list is rejected rather than read as 'allow everything'")
        void nullListRejected() {
            assertThatThrownBy(() -> new MessagingAllowedHostsConfigDTO().validatedSmtpHosts())
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("smtp");
        }

        @ParameterizedTest(name = "rejects \"{0}\"")
        @ValueSource(strings = {
                "",
                "   ",
                "*",                    // would defeat the allowlist entirely
                "*.",
                "*.in",                 // a whole public suffix
                "localhost",            // single label, an internal name
                "smtp.*.gov.in",        // wildcard anywhere but the leading label
                "-bad.example.com",
                "bad-.example.com",
                "smtp mp.gov.in",
                "smtp.mp.gov.in/path"
        })
        @DisplayName("Rejects patterns that are not a host name or a *.suffix wildcard")
        void rejectsInvalidPatterns(String pattern) {
            assertThatThrownBy(() -> of(pattern).validatedSmtpHosts())
                    .isInstanceOf(InvalidConfigValueException.class);
        }

        @Test
        @DisplayName("Rejects more entries than the cap")
        void rejectsTooManyEntries() {
            String[] patterns = new String[MessagingAllowedHostsConfigDTO.MAX_PATTERNS + 1];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = "smtp" + i + ".example.com";
            }

            assertThatThrownBy(() -> of(patterns).validatedSmtpHosts())
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining(String.valueOf(MessagingAllowedHostsConfigDTO.MAX_PATTERNS));
        }
    }

    @Nested
    @DisplayName("allowsSmtpHost")
    class Matching {

        @Test
        @DisplayName("An exact pattern matches only that host, case-insensitively")
        void exactMatch() {
            MessagingAllowedHostsConfigDTO allowed = of("smtp.mp.gov.in");

            assertThat(allowed.allowsSmtpHost("smtp.mp.gov.in")).isTrue();
            assertThat(allowed.allowsSmtpHost("SMTP.MP.GOV.IN")).isTrue();
            assertThat(allowed.allowsSmtpHost("evil.smtp.mp.gov.in")).isFalse();
            assertThat(allowed.allowsSmtpHost("smtp.mp.gov.in.evil.com")).isFalse();
        }

        @Test
        @DisplayName("A wildcard matches any depth below the suffix")
        void wildcardMatchesSubdomains() {
            MessagingAllowedHostsConfigDTO allowed = of("*.nic.in");

            assertThat(allowed.allowsSmtpHost("smtp.nic.in")).isTrue();
            assertThat(allowed.allowsSmtpHost("mail.up.nic.in")).isTrue();
        }

        @Test
        @DisplayName("A wildcard does not match the suffix itself, nor a host merely ending in it")
        void wildcardDoesNotMatchApexOrLookalike() {
            MessagingAllowedHostsConfigDTO allowed = of("*.nic.in");

            // The apex needs its own entry — a wildcard is about what is below a domain.
            assertThat(allowed.allowsSmtpHost("nic.in")).isFalse();
            // The dot in the pattern is what stops "evilnic.in" from passing a naive suffix test.
            assertThat(allowed.allowsSmtpHost("evilnic.in")).isFalse();
        }

        @Test
        @DisplayName("Null and blank hosts are not allowed")
        void nullAndBlankRejected() {
            MessagingAllowedHostsConfigDTO allowed = of("*.nic.in");

            assertThat(allowed.allowsSmtpHost(null)).isFalse();
            assertThat(allowed.allowsSmtpHost("  ")).isFalse();
        }
    }
}
