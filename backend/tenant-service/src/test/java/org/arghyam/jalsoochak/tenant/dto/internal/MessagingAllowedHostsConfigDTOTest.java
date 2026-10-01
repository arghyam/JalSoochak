package org.arghyam.jalsoochak.tenant.dto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

    /** As {@link #of}, but tolerating a null entry — what a stored list may actually hold. */
    private static MessagingAllowedHostsConfigDTO ofRaw(String... patterns) {
        return MessagingAllowedHostsConfigDTO.builder().smtp(Arrays.asList(patterns)).build();
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

        @ParameterizedTest(name = "rejects the IP literal \"{0}\"")
        @ValueSource(strings = {
                "10.0.0.5",
                "192.168.1.1",
                "127.0.0.1",
                "::1",
                "[::1]",
                "[fd00::1]",
                "*.10.0.0.5"
        })
        @DisplayName("Rejects IP literals, which HOST_PATTERN's numeric labels would otherwise store clean")
        void rejectsIpLiterals(String pattern) {
            // A literal in the list would be matched by name and never resolved, so SsrfAddressPolicy
            // — the check the allowlist exists to make meaningful — would never see it.
            assertThatThrownBy(() -> of(pattern).validatedSmtpHosts())
                    .isInstanceOf(InvalidConfigValueException.class)
                    .hasMessageContaining("IP address");
        }

        @Test
        @DisplayName("A host name with numeric labels is still a host name")
        void numericLabelsAreNotAnIpLiteral() {
            assertThat(of("smtp1.mp.gov.in", "10.mp.gov.in").validatedSmtpHosts())
                    .containsExactly("smtp1.mp.gov.in", "10.mp.gov.in");
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

        @Test
        @DisplayName("A null smtp list allows nothing rather than throwing")
        void nullListAllowsNothing() {
            assertThat(new MessagingAllowedHostsConfigDTO().allowsSmtpHost("smtp.mp.gov.in")).isFalse();
        }

        @Test
        @DisplayName("An unusable stored entry is skipped, not a reason to refuse every host")
        void unusableStoredEntryIsSkipped() {
            // A value written before validatedSmtpHosts() existed, or seeded directly. Matching
            // used to re-validate, so one entry like this made every tenant's SMTP settings write
            // fail — for any host, with an error naming nothing the caller had sent.
            MessagingAllowedHostsConfigDTO allowed = ofRaw("smtp.mp.gov.in", "", "   ", null);

            assertThat(allowed.allowsSmtpHost("smtp.mp.gov.in")).isTrue();
            assertThat(allowed.allowsSmtpHost("smtp.evil.test")).isFalse();
        }

        @Test
        @DisplayName("Matches an entry an older tenant-service stored without normalising it")
        void matchesAnUnnormalisedStoredEntry() {
            assertThat(ofRaw(" SMTP.MP.GOV.IN ").allowsSmtpHost("smtp.mp.gov.in")).isTrue();
        }

        @Test
        @DisplayName("Answers alike to message-service's twin on the same stored list")
        void agreesWithTheReadSideTwin() {
            // The two halves must not diverge: a host one service allows and the other refuses is
            // mail that silently falls back to the system default. These are the cases where the
            // write side used to throw and the read side used to answer.
            List<String> stored = Arrays.asList("smtp.mp.gov.in", "*.nic.in", "", null, "10.0.0.5");
            MessagingAllowedHostsConfigDTO writeSide =
                    MessagingAllowedHostsConfigDTO.builder().smtp(stored).build();

            for (String host : List.of("smtp.mp.gov.in", "mail.up.nic.in", "nic.in", "10.0.0.5",
                    "evilnic.in", "smtp.evil.test")) {
                assertThat(writeSide.allowsSmtpHost(host))
                        .as("host %s", host)
                        .isEqualTo(readSideAllows(stored, host));
            }
        }

        /**
         * message-service's {@code MessagingAllowedHosts.allowsSmtpHost}, transcribed. Copied rather
         * than imported because the two services are separate Maven modules; it is short enough that
         * transcribing it is the cheaper way to pin the contract, and a drift in either body shows
         * up here.
         */
        private static boolean readSideAllows(List<String> smtp, String host) {
            if (host == null || host.isBlank()) {
                return false;
            }
            String candidate = host.trim().toLowerCase(Locale.ROOT);
            for (String raw : smtp) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String pattern = raw.trim().toLowerCase(Locale.ROOT);
                if (pattern.startsWith("*.")) {
                    String suffix = pattern.substring(1);
                    if (candidate.endsWith(suffix) && candidate.length() > suffix.length()) {
                        return true;
                    }
                } else if (pattern.equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }
}
