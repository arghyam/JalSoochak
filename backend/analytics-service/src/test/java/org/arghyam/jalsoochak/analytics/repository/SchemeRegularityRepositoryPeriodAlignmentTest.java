package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SchemeRegularityRepositoryPeriodAlignmentTest {

    @Test
    void weekIsSundayAlignedCalendarWeek_monthIsCalendarAligned() throws Exception {
        SchemeRegularityRepository repo = new SchemeRegularityRepository(mock(JdbcTemplate.class));

        Object weekParts = invokeBuildPeriodSqlParts(repo, PeriodScale.WEEK);
        String weekSeriesStart = invokeStringAccessor(weekParts, "periodStartFromSeries");
        String weekSeriesEnd = invokeStringAccessor(weekParts, "periodEndFromSeries");
        String weekFactStart = invokeStringAccessor(weekParts, "periodStartFromFact");
        // Sunday-aligned calendar weeks: EXTRACT(DOW)=0 on Sunday, no rolling anchor.
        assertTrue(weekSeriesStart.contains("EXTRACT(DOW"));
        assertTrue(weekFactStart.contains("EXTRACT(DOW"));
        assertTrue(weekSeriesEnd.contains("+ 6"));
        assertFalse(weekSeriesStart.contains("params.anchor_start"));
        assertFalse(weekFactStart.contains("params.anchor_start"));
        assertFalse(weekSeriesStart.contains("DATE_TRUNC('week'"));

        Object monthParts = invokeBuildPeriodSqlParts(repo, PeriodScale.MONTH);
        String monthSeriesStart = invokeStringAccessor(monthParts, "periodStartFromSeries");
        assertTrue(monthSeriesStart.contains("DATE_TRUNC('month'"));
    }

    private static Object invokeBuildPeriodSqlParts(SchemeRegularityRepository repo, PeriodScale scale) throws Exception {
        Method m = SchemeRegularityRepository.class.getDeclaredMethod("buildPeriodSqlParts", PeriodScale.class);
        m.setAccessible(true);
        return m.invoke(repo, scale);
    }

    private static String invokeStringAccessor(Object record, String accessor) throws Exception {
        Method m = record.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return (String) m.invoke(record);
    }
}

