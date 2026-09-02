package org.arghyam.jalsoochak.telemetry.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.arghyam.jalsoochak.telemetry.security.SsrfAddressPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code reading_url} constraint, in the configuration production actually runs: http and https
 * both accepted, internal addresses refused, no host allowlist.
 *
 * <p>It shares its policy with the Glific media fetch, so the scheme rule matches that path rather
 * than being tightened here — every host in the observed production traffic is public, which is what
 * the guard turns on.
 */
@DisplayName("ValidReadingUrl — constraining a submitted meter-image URL")
class ReadingUrlConstraintValidatorTest {

    private static final ConstraintValidatorContext NO_CONTEXT = null;

    private static ReadingUrlConstraintValidator validator() {
        return validator(Set.of());
    }

    private static ReadingUrlConstraintValidator validator(Set<String> allowedHosts) {
        return new ReadingUrlConstraintValidator(ReadingUrlTestValidation.policy(allowedHosts), true);
    }


    @Nested
    @DisplayName("submissions that must keep working")
    class Compatible {

        @Test
        void acceptsABlankUrlBecauseThatIsHowAManualReadingArrives() {
            // The large majority of rows on this endpoint carry no URL at all. They are never
            // fetched, and rejecting them here would break manual readings outright.
            assertThat(validator().isValid(null, NO_CONTEXT)).isTrue();
            assertThat(validator().isValid("", NO_CONTEXT)).isTrue();
            assertThat(validator().isValid("   ", NO_CONTEXT)).isTrue();
        }

        @ParameterizedTest(name = "accepts {0}")
        @ValueSource(strings = {
                "https://adc-ecos.enlightcloud.com/uploads/meter/abc.jpg",
                "https://nyc3.digitaloceanspaces.com/bucket/meter/abc.jpg",
                "https://sumatoimg.nyc3.digitaloceanspaces.com/meter/abc.jpg",
                "https://flowvision-test.s3.ap-south-1.amazonaws.com/meter/abc.jpg",
                "https://filemanager.gupshup.io/fm/wamedia/x/abc.jpg"
        })
        void acceptsEveryHostSeenInProductionTraffic(String url) {
            assertThat(validator().isValid(url, NO_CONTEXT)).isTrue();
        }

        @ParameterizedTest(name = "accepts {0}")
        @ValueSource(strings = {
                "00ovfMsOZmMExssyoH3hQcK8aXxMS4sKBxvUBPnY.jpg",
                "zzJKgvs8niEt215LdtzXSjaniJqB9aHf6TBOKhzd.jpg",
                "uploads/meter/abc.jpg"
        })
        void acceptsABareObjectKeyBecauseItNamesNoHost(String value) {
            // ~0.08% of historical submissions are shaped like this and are resolved downstream
            // against a fixed base. Naming no host, they steer no request — refusing them would
            // break a live shape while closing nothing.
            assertThat(validator().isValid(value, NO_CONTEXT)).isTrue();
        }

        @Test
        void acceptsThoseHostsUnderAnAllowlistToo() {
            ReadingUrlConstraintValidator allowlisted = validator(Set.of(
                    "adc-ecos.enlightcloud.com",
                    "nyc3.digitaloceanspaces.com",
                    "flowvision-test.s3.ap-south-1.amazonaws.com",
                    "filemanager.gupshup.io"));

            assertThat(allowlisted.isValid("https://adc-ecos.enlightcloud.com/a.jpg", NO_CONTEXT)).isTrue();
            // Spaces uses path-style buckets, so the subdomain sender is covered by the parent entry.
            assertThat(allowlisted.isValid("https://sumatoimg.nyc3.digitaloceanspaces.com/a.jpg", NO_CONTEXT)).isTrue();
            assertThat(allowlisted.isValid("https://img.freepik.com/stock.jpg", NO_CONTEXT)).isFalse();
        }
    }

    @Nested
    @DisplayName("submissions that are now refused")
    class Refused {

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
                "http://169.254.169.254/latest/meta-data/",
                "http://127.0.0.1:8084/api/v1/telemetry/readings",
                "https://10.0.0.5/internal.jpg",
                "file:///etc/passwd",
                "javascript:alert(1)",
                "data:image/png;base64,iVBORw0KGgo="
        })
        void refusesADestinationOrSchemeThatIsNotAnImageOnThePublicInternet(String url) {
            assertThat(validator().isValid(url, NO_CONTEXT)).isFalse();
        }

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
                "//169.254.169.254/latest/meta-data/",   // protocol-relative: carries its own authority
                "//evil.example/x.jpg",
                "../../../etc/passwd",                   // climbs out of whatever base resolves it
                "uploads/../../secret.jpg"
        })
        void refusesARelativeReferenceThatCouldEscapeItsBase(String value) {
            assertThat(validator().isValid(value, NO_CONTEXT)).isFalse();
        }

        @Test
        void stillRefusesAnInternalDestinationOverPlainHttp() {
            // Scheme is not the control here; the address policy is.
            assertThat(validator().isValid("http://adc-ecos.enlightcloud.com/a.jpg", NO_CONTEXT)).isTrue();
            assertThat(validator().isValid("http://192.168.0.10/a.jpg", NO_CONTEXT)).isFalse();
        }
    }

    @Test
    void passesEverythingThroughWhenTheSharedPolicyIsDisabled() {
        MediaUrlValidator disabled = new MediaUrlValidator(false, false, Set.of(),
                new SsrfAddressPolicy(false), ReadingUrlTestValidation.publicNameResolver());

        assertThat(new ReadingUrlConstraintValidator(disabled, true)
                .isValid("http://169.254.169.254/latest/meta-data/", NO_CONTEXT)).isTrue();
    }

    @Test
    void passesEverythingThroughWhenOnlyThisPathIsDisabled() {
        // The per-path switch must stand down this rule while leaving the shared policy — and so the
        // guard on the unauthenticated Glific webhook — fully armed.
        ReadingUrlConstraintValidator off =
                new ReadingUrlConstraintValidator(ReadingUrlTestValidation.policy(), false);

        assertThat(off.isValid("http://169.254.169.254/latest/meta-data/", NO_CONTEXT)).isTrue();
        assertThat(ReadingUrlTestValidation.policy().validate("https://ok.example/a.jpg")).isNotNull();
    }
}
