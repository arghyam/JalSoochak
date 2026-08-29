package org.arghyam.jalsoochak.message.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Configuration
@Slf4j
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * Number of consumer threads draining {@code common-topic}.
     *
     * <p>At 1 — the previous behaviour — every event type shares a single thread, so a daily-report
     * run serialises behind itself and everything else waits: one {@code DAILY_REPORT_KPIS} event
     * per officer, each costing a PDF render, a MinIO upload and two Glific calls that are throttled
     * to 500ms apart process-wide. A login OTP published during that batch simply queues, and it
     * expires in ten minutes.
     *
     * <p><strong>Requires {@code common-topic} to have at least this many partitions.</strong>
     * Kafka assigns whole partitions to consumers, so on a 1-partition topic the extra threads sit
     * idle and this setting does nothing. Nothing in this repo declares a partition count, so a
     * broker-auto-created topic has exactly one — check with {@code kafka-topics --describe} and
     * raise it with {@code kafka-topics --alter --partitions N} before expecting any effect.
     *
     * <p>Repartitioning is safe here: every producer calls {@code send(topic, json)} without a key,
     * so records are already distributed round-robin with no ordering guarantee to lose.
     */
    @Value("${spring.kafka.listener.concurrency:3}")
    private int listenerConcurrency;

    /** Threads for {@link #OTP_TOPIC}. Separate from {@link #listenerConcurrency} on purpose. */
    @Value("${kafka.topic.otp.concurrency:2}")
    private int otpListenerConcurrency;

    /**
     * Optional so the container factories still build in slice tests that bring up no metrics
     * infrastructure; in a running service the actuator starter always supplies one.
     */
    private final MeterRegistry meterRegistry;

    /**
     * Boot's binding of {@code spring.kafka.*}. Optional for the same reason as the registry above —
     * slice tests construct this class directly — in which case {@link #consumerFactory()} falls back
     * to the explicitly-set properties alone.
     */
    private final KafkaProperties kafkaProperties;

    public KafkaConfig(ObjectProvider<MeterRegistry> meterRegistryProvider,
                       ObjectProvider<KafkaProperties> kafkaPropertiesProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.kafkaProperties = kafkaPropertiesProvider.getIfAvailable();
    }

    // ── Producer ──────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── Consumer ──────────────────────────────────────────────

    /**
     * Builds the consumer from {@code spring.kafka.consumer.*} <em>and then</em> pins what this class
     * depends on.
     *
     * <p>Seeding from {@link KafkaProperties#buildConsumerProperties()} is the part that matters.
     * Declaring this bean backs off Boot's own auto-configured {@code ConsumerFactory}, so the
     * previous version — which built a bare four-entry map — silently discarded every consumer
     * setting in {@code application.yml}. {@code max-poll-records}, {@code max.poll.interval.ms},
     * {@code session.timeout.ms} and {@code enable-auto-commit} were all dead config: the running
     * consumer used Kafka's client defaults instead, i.e. <strong>500 records per poll against a
     * 5-minute</strong> {@code max.poll.interval.ms}, not the 50-against-10-minutes the file
     * describes.
     *
     * <p>That combination is what makes a slow batch dangerous. The interval is measured between
     * {@code poll()} calls, so it has to cover the whole batch: 500 records that each cost a SendGrid
     * call (20s cap), a Glific call (30s cap) or a PDF render plus a MinIO upload cannot finish inside
     * five minutes. The consumer is then evicted from its group mid-batch, its offsets are never
     * committed, and the partition is reassigned — so every record it had already processed is
     * redelivered and every notification in it is sent a second time. The bounded budget in
     * {@code application.yml} is what prevents that, and it only takes effect now that these
     * properties are actually read.
     *
     * <p>Explicit puts win over the file for the four settings the code itself relies on: the
     * deserializers are fixed by the {@code String} listener signature, and bootstrap/group-id keep
     * the existing {@code @Value} contract so an override reaches both this bean and the rest of the
     * class.
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        // null SslBundles: this service talks PLAINTEXT to the broker and configures no
        // spring.ssl.bundle, which is the only thing the argument is consulted for.
        Map<String, Object> props = kafkaProperties == null
                ? new HashMap<>()
                : new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(listenerConcurrency);

        ExponentialBackOff backOff = new ExponentialBackOff(10_000L, 2.0);
        backOff.setMaxInterval(60_000L);      // cap at 60s per retry
        // 4 retries — 10s, 20s, 40s, 60s (130s of waiting) — then the recoverer runs. The limit is
        // compared against the elapsed time *before* each interval is handed out, so a 90s budget
        // still permits the 60s attempt that starts at 70s.
        //
        // What bounds this is the poll budget, not the total ladder: each rung is a separate sleep
        // followed by a seek and a fresh poll, so the longest single rung (60s) is what has to fit
        // between two poll() calls alongside the rest of the batch. See the arithmetic against
        // max-poll-records in application.yml — that is the number to redo if either changes.
        backOff.setMaxElapsedTime(90_000L);
        // Route exhausted messages to <topic>.DLT rather than silently dropping them
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                neverBlockingRecoverer(new DeadLetterPublishingRecoverer(kafkaTemplate()), meterRegistry), backOff);
        // Deserialization failures are permanent — skip retries and go straight to DLT
        errorHandler.addNotRetryableExceptions(DeserializationException.class);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    // ── OTP: its own topic, its own threads ───────────────────────

    /**
     * Login OTPs travel on a dedicated topic rather than {@code common-topic}, for two reasons.
     *
     * <p><strong>Latency.</strong> {@code common-topic} is a shared bus and a daily-report run puts
     * one event per officer on it, each costing a PDF render, a MinIO upload and Glific calls
     * throttled 500ms apart process-wide. An OTP queued behind that batch can pass its ten-minute
     * expiry before it is ever sent. A separate topic means a separate listener container with its
     * own threads, so batch traffic cannot delay a login.
     *
     * <p><strong>Blast radius.</strong> Six services subscribe to {@code common-topic}, so every
     * OTP event — plaintext code and phone number — was being delivered to five services with no
     * use for it. Only message-service subscribes here.
     */
    public static final String OTP_TOPIC = "otp-topic";

    @Value("${kafka.topic.otp.partitions:3}")
    private int otpTopicPartitions;

    @Value("${kafka.topic.otp.replicas:1}")
    private short otpTopicReplicas;

    /**
     * Declares {@code otp-topic} so {@code KafkaAdmin} creates it at startup. Brokers running with
     * {@code auto.create.topics.enable=false} would otherwise leave the listener stuck on a topic
     * that never appears, silently dropping every login OTP.
     *
     * <p>Creation only — Kafka does not shrink or grow partitions on an existing topic through this
     * bean, so changing the count later still needs {@code kafka-topics --alter}.
     */
    @Bean
    public NewTopic otpTopic() {
        return TopicBuilder.name(OTP_TOPIC)
                .partitions(otpTopicPartitions)
                .replicas(otpTopicReplicas)
                .build();
    }

    /**
     * Container factory for {@link #OTP_TOPIC}, deliberately <em>not</em> sharing the common-topic
     * factory.
     *
     * <p>The difference that matters is the back-off: {@code FixedBackOff(0, 0)} means no retries at
     * all. Retrying an OTP is actively harmful — the common-topic ladder's last rung lands ~130s out,
     * past the 60s resend cooldown, and {@code OtpService.requestOtp} revokes the previous code when
     * the user resends, so a late redelivery hands them a dead code that burns one of their three
     * verification attempts. Encoding that here means the rule holds even if a handler someday throws.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> otpListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(otpListenerConcurrency);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                neverBlockingRecoverer(new DeadLetterPublishingRecoverer(kafkaTemplate()), meterRegistry),
                otpBackOff());
        errorHandler.addNotRetryableExceptions(DeserializationException.class);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    /**
     * No retries, ever, for a login OTP — {@code maxAttempts = 0}.
     *
     * <p>Extracted so the decision is assertable on its own: Spring keeps the configured back-off
     * behind {@code DefaultErrorHandler}'s package-private internals, and a test that reaches in
     * there breaks on a Spring upgrade without the policy having changed.
     */
    static FixedBackOff otpBackOff() {
        return new FixedBackOff(0L, 0L);
    }

    /**
     * Wraps the dead-letter recoverer so that a failed DLT publish cannot turn bounded retries into an
     * endless loop.
     *
     * <p>When the recoverer throws — most commonly because {@code <topic>.DLT} does not exist on a
     * broker with {@code auto.create.topics.enable=false} — {@link DefaultErrorHandler} logs
     * "Error handler threw an exception" and seeks back to the failed offset. The record is then
     * re-consumed from the top, forever. For a listener whose handlers have external side effects that
     * is not a retry, it is a broadcast: every pass around the loop re-ran the notification handler and
     * sent the officer another WhatsApp message.</p>
     *
     * <p>Swallowing the publish failure lets the offset commit, so the poison record is dropped after
     * its normal retry budget. The record's topic/partition/offset is logged at ERROR so the event can
     * still be replayed from the log if it matters. The payload itself stays at DEBUG and with phone
     * numbers redacted: notification events carry operator and officer mobile numbers, which are PII
     * and must never reach an INFO/WARN/ERROR line. Exception <em>messages</em> are held back for the
     * same reason — a Glific or JDBC failure routinely echoes the payload it choked on — so ERROR
     * carries the exception types and DEBUG carries the stack traces.</p>
     */
    static ConsumerRecordRecoverer neverBlockingRecoverer(ConsumerRecordRecoverer delegate) {
        return neverBlockingRecoverer(delegate, null);
    }

    /**
     * @param meterRegistry counts both outcomes so the silent paths become visible. Nothing consumes
     *                      the dead-letter topics, so without a metric a dead-lettered record and a
     *                      dropped one look identical from outside: nothing happens either way.
     */
    static ConsumerRecordRecoverer neverBlockingRecoverer(ConsumerRecordRecoverer delegate,
                                                          MeterRegistry meterRegistry) {
        return (consumerRecord, exception) -> {
            try {
                delegate.accept(consumerRecord, exception);
                count(meterRegistry, "deadlettered", consumerRecord.topic());
            } catch (Exception dltFailure) {
                // The record is about to be dropped outright — the one outcome that must page someone.
                count(meterRegistry, "dropped", consumerRecord.topic());
                log.error("[Kafka] Dead-letter publish failed for {}-{}@{}; dropping the record to avoid an"
                                + " endless redelivery loop (handlers with external side effects would re-run"
                                + " on every pass). Original failure: {}. DLT failure: {}."
                                + " Enable DEBUG on this logger for the payload and stack traces.",
                        consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset(),
                        exception == null ? "unknown" : exception.getClass().getName(),
                        dltFailure.getClass().getName());
                log.debug("[Kafka] Dropped record {}-{}@{} payload (phone numbers redacted): {}",
                        consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset(),
                        redactPhoneNumbers(consumerRecord.value()), dltFailure);
            }
        };
    }

    /**
     * Name of the counter every dead-letter path in this service increments.
     *
     * <p>Alert on it in Grafana — {@code increase(jalsoochak_deadletter_total[15m]) > 0} for
     * {@code outcome="dropped"} is a page (a notification was lost outright), and the same for
     * {@code outcome="deadlettered"} is a ticket (it is recoverable, but only if someone looks).
     */
    public static final String DEADLETTER_COUNTER = "jalsoochak.deadletter";

    /**
     * Tag keys must match {@code NotificationEventRouter.countDeadLetter} exactly. Prometheus
     * requires every sample of a metric to carry the same label set, and Micrometer enforces that
     * at registration: the second registration of {@code jalsoochak.deadletter} with a different
     * set of tag keys throws, so a mismatch here would break whichever path happened to run second
     * — silently disarming the alerts this counter exists to feed.
     *
     * <p>The container-level recoverer has no parsed event to name, so {@code event_type} is
     * {@code "unknown"} rather than absent.
     */
    private static void count(MeterRegistry meterRegistry, String outcome, String topic) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(DEADLETTER_COUNTER)
                .tag("outcome", outcome)
                .tag("topic", topic == null ? "unknown" : topic)
                .tag("event_type", "unknown")
                .register(meterRegistry)
                .increment();
    }

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{10,}");

    /**
     * Masks anything that looks like a phone number — a run of 10 or more digits, which covers both the
     * bare 10-digit mobile and the {@code 91XXXXXXXXXX} E.164 form used throughout these events —
     * keeping the last four digits so two records can still be told apart. Deliberately blunt: it will
     * also mask a long numeric id, which is the right trade at DEBUG.
     */
    static String redactPhoneNumbers(Object payload) {
        if (payload == null) {
            return null;
        }
        return DIGIT_RUN.matcher(payload.toString()).replaceAll(m -> "*".repeat(m.group().length() - 4)
                + m.group().substring(m.group().length() - 4));
    }
}
