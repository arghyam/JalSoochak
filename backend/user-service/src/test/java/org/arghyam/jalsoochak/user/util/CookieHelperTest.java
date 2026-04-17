package org.arghyam.jalsoochak.user.util;

import org.arghyam.jalsoochak.user.config.properties.CookieProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CookieHelper")
class CookieHelperTest {

    private static CookieHelper helper(boolean secure, String sameSite) {
        return new CookieHelper(new CookieProperties(secure, sameSite));
    }

    @Nested
    @DisplayName("buildRefreshCookie")
    class BuildRefreshCookie {

        @Test
        @DisplayName("cookie has the correct name and value")
        void correctNameAndValue() {
            ResponseCookie cookie = helper(false, "Strict").buildRefreshCookie("my-token", 3600);
            assertThat(cookie.getName()).isEqualTo(CookieHelper.REFRESH_COOKIE_NAME);
            assertThat(cookie.getValue()).isEqualTo("my-token");
        }

        @Test
        @DisplayName("cookie is HttpOnly")
        void isHttpOnly() {
            ResponseCookie cookie = helper(false, "Strict").buildRefreshCookie("tok", 3600);
            assertThat(cookie.isHttpOnly()).isTrue();
        }

        @Test
        @DisplayName("cookie reflects the configured Secure flag")
        void reflectsSecureFlag() {
            ResponseCookie secureCookie = helper(true, "Strict").buildRefreshCookie("tok", 3600);
            ResponseCookie insecureCookie = helper(false, "Strict").buildRefreshCookie("tok", 3600);
            assertThat(secureCookie.isSecure()).isTrue();
            assertThat(insecureCookie.isSecure()).isFalse();
        }

        @Test
        @DisplayName("cookie reflects the configured SameSite value")
        void reflectsSameSite() {
            ResponseCookie cookie = helper(false, "Lax").buildRefreshCookie("tok", 3600);
            assertThat(cookie.getSameSite()).isEqualTo("Lax");
        }

        @Test
        @DisplayName("cookie has the correct max-age")
        void correctMaxAge() {
            ResponseCookie cookie = helper(false, "Strict").buildRefreshCookie("tok", 7200);
            assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(7200);
        }
    }

    @Nested
    @DisplayName("clearRefreshCookie")
    class ClearRefreshCookie {

        @Test
        @DisplayName("sets max-age to 0 and empty value to expire the cookie")
        void expiresCookie() {
            ResponseCookie cookie = helper(false, "Strict").clearRefreshCookie();
            assertThat(cookie.getName()).isEqualTo(CookieHelper.REFRESH_COOKIE_NAME);
            assertThat(cookie.getValue()).isEmpty();
            assertThat(cookie.getMaxAge().getSeconds()).isZero();
        }

        @Test
        @DisplayName("cleared cookie is still HttpOnly")
        void isHttpOnly() {
            ResponseCookie cookie = helper(false, "Strict").clearRefreshCookie();
            assertThat(cookie.isHttpOnly()).isTrue();
        }
    }

    @Nested
    @DisplayName("cookieName")
    class CookieName {

        @Test
        @DisplayName("returns the expected constant name")
        void returnsConstantName() {
            assertThat(helper(false, "Strict").cookieName()).isEqualTo("refresh_token");
        }
    }
}
