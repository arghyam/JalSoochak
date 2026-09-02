package org.arghyam.jalsoochak.telemetry.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pre-flight policy for a meter-image URL that arrived on the webhook.
 *
 * <p>Name resolution is stubbed throughout: the rules under test are about what the policy does with
 * an answer, and a test that asked real DNS would be testing the internet.
 */
@DisplayName("MediaUrlValidator — vetting a caller-supplied media URL")
class MediaUrlValidatorTest {

    private static final byte[] PUBLIC_IPV4 = {8, 8, 8, 8};

    /** Answers every name with the same fixed address. */
    private static HostResolver resolvingTo(String... literals) {
        return host -> {
            InetAddress[] addresses = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                addresses[i] = InetAddress.getByName(literals[i]);
            }
            return addresses;
        };
    }

    private static HostResolver failingResolver() {
        return host -> {
            throw new UnknownHostException(host);
        };
    }

    private static MediaUrlValidator validator() {
        return validator(Set.of(), false, true);
    }

    private static MediaUrlValidator validator(Set<String> allowedHosts, boolean requireHttps, boolean enabled) {
        return new MediaUrlValidator(enabled, requireHttps, allowedHosts,
                new SsrfAddressPolicy(false), resolvingTo("8.8.8.8"));
    }

    @Nested
    @DisplayName("accepts what the real flow sends")
    class HappyPath {

        @Test
        void acceptsAPresignedHttpsMediaUrl() {
            URI uri = validator().validate("https://media.glific.example/whatsapp/abc.jpg?sig=xyz");

            assertThat(uri.getHost()).isEqualTo("media.glific.example");
            assertThat(uri.getQuery()).isEqualTo("sig=xyz");
        }

        @Test
        void acceptsPlainHttpUnlessHttpsIsRequired() {
            assertThatCode(() -> validator().validate("http://media.glific.example/abc.jpg"))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> validator(Set.of(), true, true).validate("http://media.glific.example/abc.jpg"))
                    .isInstanceOf(MediaUrlNotAllowedException.class)
                    .extracting(e -> ((MediaUrlNotAllowedException) e).getReason())
                    .isEqualTo("https required");
        }

        @Test
        void trimsSurroundingWhitespace() {
            assertThatCode(() -> validator().validate("  https://media.glific.example/abc.jpg  "))
                    .doesNotThrowAnyException();
        }

        @Test
        void tellsTheCallerNothingAboutWhyItRefused() {
            // The operator-facing text is the same for every rejection, so a probe cannot use the
            // reply to map the internal network.
            assertThatThrownBy(() -> validator().validate("https://media.glific.example@169.254.169.254/"))
                    .hasMessage("Invalid media. Please send a clear meter image.");
        }
    }

    @Nested
    @DisplayName("network destination")
    class Destination {

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
                "http://169.254.169.254/latest/meta-data/",
                "http://localhost:8080/actuator/env",
                "http://127.0.0.1:8084/api/v1/telemetry/readings",
                "http://[::1]:8080/",
                "http://10.0.0.5/",
                "https://192.168.1.1/admin",
                "http://2130706433/",              // 127.0.0.1 in decimal form
                "http://0x7f000001/"               // 127.0.0.1 in hex form
        })
        void refusesAUrlPointingIntoTheInternalNetwork(String url) {
            // The literals resolve to themselves; only a name would reach the stub resolver.
            MediaUrlValidator validator = new MediaUrlValidator(true, false, Set.of(),
                    new SsrfAddressPolicy(false), HostResolver.SYSTEM);

            assertThatThrownBy(() -> validator.validate(url))
                    .isInstanceOf(MediaUrlNotAllowedException.class);
        }

        @Test
        void refusesANameThatResolvesToAnInternalAddress() {
            MediaUrlValidator validator = new MediaUrlValidator(true, false, Set.of(),
                    new SsrfAddressPolicy(false), resolvingTo("169.254.169.254"));

            assertThatThrownBy(() -> validator.validate("https://rebind.example/img.jpg"))
                    .isInstanceOf(MediaUrlNotAllowedException.class)
                    .extracting(e -> ((MediaUrlNotAllowedException) e).getReason())
                    .isEqualTo("non-public address");
        }

        @Test
        void refusesANameWithEvenOneInternalAnswer() {
            // Filtering to the public answers would leave a working round of DNS rebinding.
            MediaUrlValidator validator = new MediaUrlValidator(true, false, Set.of(),
                    new SsrfAddressPolicy(false), resolvingTo("8.8.8.8", "127.0.0.1"));

            assertThatThrownBy(() -> validator.validate("https://rebind.example/img.jpg"))
                    .isInstanceOf(MediaUrlNotAllowedException.class);
        }

        @Test
        void refusesANameThatDoesNotResolve() {
            MediaUrlValidator validator = new MediaUrlValidator(true, false, Set.of(),
                    new SsrfAddressPolicy(false), failingResolver());

            assertThatThrownBy(() -> validator.validate("https://nowhere.example/img.jpg"))
                    .isInstanceOf(MediaUrlNotAllowedException.class)
                    .extracting(e -> ((MediaUrlNotAllowedException) e).getReason())
                    .isEqualTo("unresolvable host");
        }
    }

    @Nested
    @DisplayName("URL shape")
    class Shape {

        @ParameterizedTest(name = "refuses scheme in {0}")
        @ValueSource(strings = {
                "file:///etc/passwd",
                "ftp://internal.example/secret",
                "gopher://127.0.0.1:6379/_INFO",
                "jar:https://media.glific.example/a.jar!/x"
        })
        void refusesAnythingThatIsNotHttp(String url) {
            assertThatThrownBy(() -> validator().validate(url))
                    .isInstanceOf(MediaUrlNotAllowedException.class);
        }

        @Test
        void refusesCredentialsInTheAuthority() {
            // A standard way to make a lenient parser disagree with the HTTP client about the host.
            assertThatThrownBy(() -> validator().validate("https://media.glific.example@169.254.169.254/"))
                    .isInstanceOf(MediaUrlNotAllowedException.class)
                    .extracting(e -> ((MediaUrlNotAllowedException) e).getReason())
                    .isEqualTo("userinfo present");
        }

        @Test
        void refusesARelativeOrHostlessUrl() {
            assertThatThrownBy(() -> validator().validate("/latest/meta-data/"))
                    .isInstanceOf(MediaUrlNotAllowedException.class);
        }

        @Test
        void refusesBlankInput() {
            assertThatThrownBy(() -> validator().validate("   "))
                    .isInstanceOf(MediaUrlNotAllowedException.class)
                    .extracting(e -> ((MediaUrlNotAllowedException) e).getReason())
                    .isEqualTo("blank media url");
        }
    }

    @Nested
    @DisplayName("host allowlist")
    class Allowlist {

        private MediaUrlValidator allowlisted() {
            return validator(Set.of("glific.example", "media.glific.org"), false, true);
        }

        @Test
        void allowsAnyPublicHostWhileTheAllowlistIsEmpty() {
            // The default, so switching the guard on does not require knowing Glific's hosts first.
            assertThatCode(() -> validator().validate("https://anything.example/img.jpg"))
                    .doesNotThrowAnyException();
        }

        @Test
        void allowsAnExactMatchAndItsSubdomains() {
            assertThatCode(() -> allowlisted().validate("https://glific.example/img.jpg"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> allowlisted().validate("https://cdn.glific.example/img.jpg"))
                    .doesNotThrowAnyException();
        }

        @Test
        void matchesCaseInsensitivelyAndIgnoresARootDot() {
            assertThatCode(() -> allowlisted().validate("https://CDN.Glific.Example./img.jpg"))
                    .doesNotThrowAnyException();
        }

        @Test
        void refusesAHostThatMerelyLooksLikeAnAllowedOne() {
            assertThatThrownBy(() -> allowlisted().validate("https://glific.example.evil.test/img.jpg"))
                    .isInstanceOf(MediaUrlNotAllowedException.class)
                    .extracting(e -> ((MediaUrlNotAllowedException) e).getReason())
                    .isEqualTo("host not allowlisted");

            assertThatThrownBy(() -> allowlisted().validate("https://notglific.example/img.jpg"))
                    .isInstanceOf(MediaUrlNotAllowedException.class);
        }

        @Test
        void parsesACommaSeparatedConfigurationValue() {
            assertThat(MediaUrlValidator.parseAllowedHosts(" a.example , b.example ,"))
                    .containsExactly("a.example", "b.example");
            assertThat(MediaUrlValidator.parseAllowedHosts("")).isEmpty();
            assertThat(MediaUrlValidator.parseAllowedHosts(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("kill switch")
    class KillSwitch {

        @Test
        void passesEverythingThroughWhenTheGuardIsDisabled() {
            MediaUrlValidator disabled = validator(Set.of("glific.example"), true, false);

            assertThatCode(() -> disabled.validate("http://169.254.169.254/latest/meta-data/"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("redirect targets")
    class RedirectTargets {

        @Test
        void appliesTheSamePolicyToAResolvedRedirect() {
            MediaUrlValidator validator = new MediaUrlValidator(true, false, Set.of(),
                    new SsrfAddressPolicy(false), HostResolver.SYSTEM);

            assertThatThrownBy(() -> validator.validateTarget(URI.create("http://169.254.169.254/latest/meta-data/")))
                    .isInstanceOf(MediaUrlNotAllowedException.class);
        }

        @Test
        void acceptsAPublicRedirect() {
            assertThatCode(() -> validator().validateTarget(URI.create("https://cdn.example/img.jpg")))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void resolvesAnIpv6LiteralWithoutItsBrackets() throws UnknownHostException {
        // URI keeps the brackets; a resolver handed "[::1]" would not recognise the address.
        InetAddress[] answered = {InetAddress.getByAddress(PUBLIC_IPV4)};
        MediaUrlValidator validator = new MediaUrlValidator(true, false, Set.of(),
                new SsrfAddressPolicy(false), host -> {
            assertThat(host).doesNotContain("[").doesNotContain("]");
            return answered;
        });

        assertThatCode(() -> validator.validate("https://[2606:4700:4700::1111]/img.jpg"))
                .doesNotThrowAnyException();
    }
}
