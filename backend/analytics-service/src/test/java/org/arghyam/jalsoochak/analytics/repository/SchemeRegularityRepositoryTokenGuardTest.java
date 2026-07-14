package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 fail-fast guard: {@code withWaterFragments} must reject any SQL that still contains an
 * unreplaced {@code {{...}}} token (a routing mistake — e.g. a {@code {{WS}}}/{@code {{NWS}}} query
 * sent through the water-only path, or a typo'd token) rather than executing malformed SQL.
 *
 * <p>The positive path (all tokens replaced) is exercised by every repository integration test; this
 * pins the negative path directly against the private static guard.</p>
 */
class SchemeRegularityRepositoryTokenGuardTest {

    private static String invokeWithWaterFragments(String sql) throws Exception {
        Method m = SchemeRegularityRepository.class.getDeclaredMethod("withWaterFragments", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, sql);
    }

    @Test
    void unreplacedToken_throwsIllegalState() {
        assertThatThrownBy(() -> invokeWithWaterFragments("SELECT 1 FROM t WHERE x {{WS}}"))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unreplaced SQL token");
    }

    @Test
    void fullyResolvedSql_passesThroughUnchanged() throws Exception {
        // No tokens, and the generated predicate's single-brace '{}'::int[] must NOT trip the guard.
        String sql = "SELECT 1 FROM t WHERE work_status = ANY('{}'::int[])";
        assertThat(invokeWithWaterFragments(sql)).isEqualTo(sql);
    }
}
