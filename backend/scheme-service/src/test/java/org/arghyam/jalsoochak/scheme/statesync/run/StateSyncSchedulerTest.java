package org.arghyam.jalsoochak.scheme.statesync.run;

import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StateSyncSchedulerTest {

    @Test
    void aMalformedCronFailsAtStartupNotAtTwoAm() {
        StateSyncProperties properties = new StateSyncProperties();
        properties.setFullCron("every night");
        StateSyncScheduler scheduler = new StateSyncScheduler(properties, mock(StateSyncRunner.class));

        assertThatThrownBy(scheduler::start).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registersTheConfiguredCrons() {
        StateSyncProperties properties = new StateSyncProperties();
        properties.setDeltaCron("0 */20 * * * *");
        StateSyncScheduler scheduler = new StateSyncScheduler(properties, mock(StateSyncRunner.class));

        assertThatCode(scheduler::start).doesNotThrowAnyException();
        scheduler.stop();
    }
}
