package org.arghyam.jalsoochak.message.kafka;

import org.arghyam.jalsoochak.message.service.NotificationEventRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumer {

    private final NotificationEventRouter notificationEventRouter;

    @KafkaListener(topics = "common-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        log.debug("[message-service] Received message from common-topic: {}", message);
        notificationEventRouter.route(message);
    }

    /**
     * Login OTPs, on their own topic and their own threads so a daily-report batch on
     * {@code common-topic} cannot delay a login past the OTP's ten-minute expiry.
     *
     * <p>Routes into the same {@link NotificationEventRouter} as everything else — the topic decides
     * which threads carry the event, not what happens to it.
     *
     * <p>The {@code SEND_LOGIN_OTP} case on {@code common-topic} is deliberately left in place. It
     * costs nothing while no producer uses it, and it makes the migration safe in both directions:
     * message-service can be deployed before or after user-service switches producers, and a
     * rollback of either service still delivers OTPs. Remove it only once no OTP has arrived on
     * {@code common-topic} for a full release cycle.
     */
    @KafkaListener(topics = KafkaConfig.OTP_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "otpListenerContainerFactory")
    public void consumeOtp(String message) {
        log.debug("[message-service] Received message from {}: {}", KafkaConfig.OTP_TOPIC, message);
        notificationEventRouter.route(message);
    }
}
