package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two bounded thread pools behind telemetry's background work: the WhatsApp contact sync and the
 * Kafka event publisher. Both are queue-bounded on purpose — an unbounded queue would let a WhatsApp
 * provider or broker outage accumulate work until the service runs out of memory.
 */
@DisplayName("AsyncConfig")
class AsyncConfigTest {

    private final AsyncConfig config = new AsyncConfig();

    @Test
    void whatsAppSyncExecutorIsABoundedInitialisedPool() {
        Executor executor = config.whatsAppSyncExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaxPoolSize()).isEqualTo(4);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("whatsapp-sync-");
        assertThat(pool.getThreadPoolExecutor()).isNotNull();

        pool.shutdown();
    }

    @Test
    void kafkaPublisherExecutorIsABoundedInitialisedPool() {
        Executor executor = config.kafkaPublisherExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaxPoolSize()).isEqualTo(4);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("kafka-pub-");
        assertThat(pool.getThreadPoolExecutor()).isNotNull();

        pool.shutdown();
    }

    @Test
    void whatsAppSyncExecutorActuallyRunsSubmittedWork() throws InterruptedException {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) config.whatsAppSyncExecutor();
        CountDownLatch ran = new CountDownLatch(1);

        pool.execute(ran::countDown);

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }

    @Test
    void theTwoPoolsAreIndependentInstances() {
        ThreadPoolTaskExecutor sync = (ThreadPoolTaskExecutor) config.whatsAppSyncExecutor();
        ThreadPoolTaskExecutor publisher = (ThreadPoolTaskExecutor) config.kafkaPublisherExecutor();

        // A stalled WhatsApp sync must not be able to starve Kafka publishing, or vice versa.
        assertThat(sync).isNotSameAs(publisher);

        sync.shutdown();
        publisher.shutdown();
    }
}
