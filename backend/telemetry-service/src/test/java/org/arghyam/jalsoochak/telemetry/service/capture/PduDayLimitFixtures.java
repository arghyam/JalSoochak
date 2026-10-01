package org.arghyam.jalsoochak.telemetry.service.capture;

import java.util.Optional;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/** Stubs a mocked {@link PduDayLimit} for service tests that aren't about the day's limit. */
public final class PduDayLimitFixtures {

    private PduDayLimitFixtures() {
    }

    /**
     * Every PDU run is within its day: the write runs as if there were no limit. A test that refuses a
     * run overrides this with {@code doReturn(Optional.empty())}, which doesn't run this answer.
     */
    public static void allowsEveryRun(PduDayLimit pduDayLimit) {
        lenient().doAnswer(invocation -> Optional.of(invocation.<Supplier<?>>getArgument(5).get()))
                .when(pduDayLimit).writeWithinLimit(any(), any(), any(), any(), any(), any());
    }
}
