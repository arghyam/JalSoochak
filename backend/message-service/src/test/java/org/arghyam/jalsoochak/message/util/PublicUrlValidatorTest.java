package org.arghyam.jalsoochak.message.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PublicUrlValidator}.
 *
 * <p>The values that matter most are the two real ones from the incident: the internal MinIO address
 * that Meta refused with {@code (#131053) … blocked by a destination filter}, and the public URL that
 * replaces it.</p>
 */
class PublicUrlValidatorTest {

    // ─────────────────────── the addresses from production ──────────────────────

    @Test
    void rejectsTheInternalMinioAddressThatMetaRefused() {
        String reason = PublicUrlValidator.unreachableReason(
                "http://192.168.20.143:9000/escalation-reports/daily_report_SECTION_OFFICER_16363_2026-08-19.pdf");

        assertThat(reason).contains("192.168.20.143").contains("private");
        assertThat(PublicUrlValidator.isPubliclyReachable("http://192.168.20.143:9000")).isFalse();
    }

    @Test
    void acceptsThePublicProductionUrl() {
        assertThat(PublicUrlValidator.unreachableReason(
                "https://jalsoochak.jjmbrain.in/minio/escalation-reports/daily_report_SECTION_OFFICER_16743_2026-08-19.pdf"))
                .isNull();
        assertThat(PublicUrlValidator.isPubliclyReachable("https://jalsoochak.jjmbrain.in/minio")).isTrue();
    }

    // ──────────────────────────── private addresses ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:9000",
            "http://127.0.0.1:9000",
            "http://127.1.2.3:9000",
            "http://0.0.0.0:9000",
            "http://10.0.5.12:9000",
            "http://10.255.255.255",
            "http://192.168.1.1",
            "http://172.16.0.1",
            "http://172.31.255.254",
            "http://169.254.169.254",          // link-local / cloud metadata
            "http://100.64.0.1",               // carrier-grade NAT
            "http://minio",                    // container name
            "http://minio-service",
            "http://minio.local",
            "http://minio.svc.cluster.local",
            "http://storage.internal",
            "https://[::1]:9000",
            "http://[fd00::1]:9000",           // IPv6 unique-local
    })
    void rejectsAddressesThatOnlyResolveInsideTheNetwork(String url) {
        assertThat(PublicUrlValidator.unreachableReason(url))
                .as("expected %s to be rejected", url)
                .isNotNull();
    }

    /** 172.15 and 172.32 sit outside RFC 1918's 172.16/12 block and must not be swept up with it. */
    @ParameterizedTest
    @ValueSource(strings = {"http://172.15.0.1", "http://172.32.0.1", "http://100.63.0.1", "http://100.128.0.1"})
    void acceptsAddressesJustOutsideThePrivateRanges(String url) {
        assertThat(PublicUrlValidator.unreachableReason(url))
                .as("expected %s to be accepted", url)
                .isNull();
    }

    // ──────────────────────────── public addresses ──────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "https://jalsoochak.jjmbrain.in/minio",
            "https://minio.example.com",
            "http://minio.example.com:9000/bucket/file.pdf",
            "https://8.8.8.8/bucket/file.pdf",
            "https://storage.jjmbrain.in:443/minio/",
    })
    void acceptsPubliclyResolvableAddresses(String url) {
        assertThat(PublicUrlValidator.unreachableReason(url))
                .as("expected %s to be accepted", url)
                .isNull();
    }

    // ─────────────────────────── malformed input ────────────────────────────────

    @Test
    void rejectsBlankAndNullUrls() {
        assertThat(PublicUrlValidator.unreachableReason(null)).isEqualTo("URL is blank");
        assertThat(PublicUrlValidator.unreachableReason("")).isEqualTo("URL is blank");
        assertThat(PublicUrlValidator.unreachableReason("   ")).isEqualTo("URL is blank");
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThat(PublicUrlValidator.unreachableReason("s3://bucket/file.pdf"))
                .contains("scheme must be http or https");
        assertThat(PublicUrlValidator.unreachableReason("file:///tmp/report.pdf"))
                .contains("scheme must be http or https");
    }

    @Test
    void rejectsARelativePathWithNoHost() {
        assertThat(PublicUrlValidator.unreachableReason("/escalation-reports/report.pdf")).isNotNull();
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertThat(PublicUrlValidator.unreachableReason("  https://jalsoochak.jjmbrain.in/minio  ")).isNull();
    }
}
