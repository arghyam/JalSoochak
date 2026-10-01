package org.arghyam.jalsoochak.message.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PER-TENANT-PROVIDERS: unit tests for {@link MessagingAllowedHosts} matching.
 *
 * <p>The behaviour asserted here has to agree exactly with tenant-service's
 * {@code MessagingAllowedHostsConfigDTO.allowsSmtpHost}. A host tenant-service accepts on write and
 * this class refuses at send time is a tenant whose mail silently falls back to the system default,
 * with nothing in the write path to hint at why.
 */
@DisplayName("MessagingAllowedHosts Tests")
class MessagingAllowedHostsTest {

    private static MessagingAllowedHosts hosts(String... patterns) {
        return new MessagingAllowedHosts(List.of(patterns));
    }

    @Test
    @DisplayName("an exact pattern matches only that host")
    void exactPatternMatchesOnlyThatHost() {
        MessagingAllowedHosts allowed = hosts("smtp.mp.gov.in");

        assertThat(allowed.allowsSmtpHost("smtp.mp.gov.in")).isTrue();
        assertThat(allowed.allowsSmtpHost("mail.mp.gov.in")).isFalse();
        assertThat(allowed.allowsSmtpHost("smtp.mp.gov.in.evil.test")).isFalse();
    }

    @Test
    @DisplayName("a *.suffix wildcard matches any depth below the suffix")
    void wildcardMatchesAnyDepthBelowTheSuffix() {
        MessagingAllowedHosts allowed = hosts("*.nic.in");

        assertThat(allowed.allowsSmtpHost("smtp.nic.in")).isTrue();
        assertThat(allowed.allowsSmtpHost("mail.up.nic.in")).isTrue();
    }

    @Test
    @DisplayName("a *.suffix wildcard does not match the apex")
    void wildcardDoesNotMatchTheApex() {
        // The apex needs its own entry. Otherwise "*.in" — which the writer refuses anyway — would
        // be one keystroke from allowing a whole public suffix.
        assertThat(hosts("*.nic.in").allowsSmtpHost("nic.in")).isFalse();
    }

    @Test
    @DisplayName("a host that merely ends with the suffix text does not match")
    void suffixMustBeOnALabelBoundary() {
        // "evilnic.in" ends with "nic.in" but not with ".nic.in", which is the whole reason the
        // stored pattern's dot is kept in the comparison.
        assertThat(hosts("*.nic.in").allowsSmtpHost("evilnic.in")).isFalse();
    }

    @Test
    @DisplayName("matching ignores case on both sides")
    void matchingIgnoresCase() {
        assertThat(hosts("SMTP.MP.GOV.IN").allowsSmtpHost("smtp.mp.gov.in")).isTrue();
        assertThat(hosts("*.nic.in").allowsSmtpHost("SMTP.NIC.IN")).isTrue();
        assertThat(hosts(" smtp.mp.gov.in ").allowsSmtpHost("smtp.mp.gov.in")).isTrue();
    }

    @Test
    @DisplayName("an empty, null or blank-entry list allows nothing")
    void failsClosed() {
        assertThat(MessagingAllowedHosts.NONE.allowsSmtpHost("smtp.mp.gov.in")).isFalse();
        assertThat(hosts().allowsSmtpHost("smtp.mp.gov.in")).isFalse();
        assertThat(new MessagingAllowedHosts(null).allowsSmtpHost("smtp.mp.gov.in")).isFalse();
        assertThat(new MessagingAllowedHosts(Arrays.asList(null, "  ")).allowsSmtpHost("smtp.mp.gov.in"))
                .isFalse();
    }

    @Test
    @DisplayName("a null or blank host is never allowed")
    void nullOrBlankHostIsNeverAllowed() {
        MessagingAllowedHosts allowed = hosts("smtp.mp.gov.in", "*.nic.in");

        assertThat(allowed.allowsSmtpHost(null)).isFalse();
        assertThat(allowed.allowsSmtpHost("")).isFalse();
        assertThat(allowed.allowsSmtpHost("   ")).isFalse();
    }

    @Test
    @DisplayName("the list is defensively copied")
    void listIsDefensivelyCopied() {
        List<String> mutable = new java.util.ArrayList<>(List.of("smtp.mp.gov.in"));
        MessagingAllowedHosts allowed = new MessagingAllowedHosts(mutable);

        mutable.add("evil.test");

        assertThat(allowed.allowsSmtpHost("evil.test")).isFalse();
    }
}
