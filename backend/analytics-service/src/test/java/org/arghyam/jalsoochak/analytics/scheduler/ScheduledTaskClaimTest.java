package org.arghyam.jalsoochak.analytics.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskClaimTest {

    /** 00:00 on 2026-10-06 in IST, still 2026-10-05 in UTC. */
    private static final Clock MIDNIGHT_IST =
            Clock.fixed(Instant.parse("2026-10-05T18:30:00Z"), ZoneOffset.UTC);
    private static final String KEY = "analytics-service:scheduler-claim:lgd-state-warm-cache:2026-10-06";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ScheduledTaskClaim claim;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        claim = new ScheduledTaskClaim(redisTemplate, "analytics-service", "pod-a");
        ReflectionTestUtils.setField(claim, "clock", MIDNIGHT_IST);
    }

    @Test
    void claimToday_winsWhenNoOtherInstanceClaimedTheIstDay() {
        when(valueOperations.setIfAbsent(KEY, "pod-a", Duration.ofDays(2))).thenReturn(true);

        assertThat(claim.claimToday("lgd-state-warm-cache")).isTrue();
    }

    @Test
    void claimToday_losesWhenAnotherInstanceAlreadyClaimedIt() {
        when(valueOperations.setIfAbsent(KEY, "pod-a", Duration.ofDays(2))).thenReturn(false);

        assertThat(claim.claimToday("lgd-state-warm-cache")).isFalse();
    }

    @Test
    void claimToday_losesWhenRedisCannotBeReached() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(claim.claimToday("lgd-state-warm-cache")).isFalse();
    }
}
