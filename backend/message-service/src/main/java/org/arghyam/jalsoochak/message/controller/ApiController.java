package org.arghyam.jalsoochak.message.controller;

import org.arghyam.jalsoochak.message.dto.NotificationRequest;
import org.arghyam.jalsoochak.message.dto.SampleDTO;
import org.arghyam.jalsoochak.message.dto.TriggerWelcomeMessageRequest;
import org.arghyam.jalsoochak.message.dto.TriggerWelcomeMessageResponse;
import org.arghyam.jalsoochak.message.kafka.KafkaProducer;
import org.arghyam.jalsoochak.message.service.BusinessService;
import org.arghyam.jalsoochak.message.service.NotificationService;
import org.arghyam.jalsoochak.message.service.WelcomeMessageTriggerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/v1/message")
@RequiredArgsConstructor
@Slf4j
public class ApiController {

    private final BusinessService businessService;
    private final NotificationService notificationService;
    private final KafkaProducer kafkaProducer;
    private final WelcomeMessageTriggerService welcomeMessageTriggerService;

    // ── GET all notifications (hardcoded) ─────────────────────

    @GetMapping("/notifications")
    public ResponseEntity<List<SampleDTO>> getAllNotifications() {
        log.info("GET /api/v1/message/notifications called");
        return ResponseEntity.ok(businessService.getAllNotifications());
    }

    // ── POST send a notification via the specified channel ────

    // Offloaded to boundedElastic: WebhookChannel and EmailChannel call .block() internally,
    // which must not run on the Netty event-loop thread. GlificGraphQLClient also uses .block()
    // but is only ever called from Kafka listener threads, so it is unaffected by this change.
    @PostMapping("/notifications")
    public Mono<ResponseEntity<String>> sendNotification(@RequestBody NotificationRequest request) {
        log.info("POST /api/v1/message/notifications called – channel={}", request.getChannel());
        log.debug("POST /api/v1/message/notifications called – channel={}, recipient={}",
                request.getChannel(), request.getRecipient());
        return Mono.fromCallable(() -> notificationService.send(request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    // ── POST dispatch a Kafka event ───────────────────────────

    @PostMapping("/events")
    public ResponseEntity<String> dispatchEvent(@RequestBody String message) {
        log.info("POST /api/v1/message/events called with message: {}", message);
        kafkaProducer.sendMessage(message);
        return ResponseEntity.ok("Message published to message-service-topic");
    }

    @PostMapping("/trigger-welcome-message")
    public ResponseEntity<TriggerWelcomeMessageResponse> triggerWelcomeMessage(
            @Valid @RequestBody TriggerWelcomeMessageRequest request) {
        log.info("POST /api/v1/message/trigger-welcome-message called");
        TriggerWelcomeMessageResponse response = welcomeMessageTriggerService.triggerByPhone(request.getPhoneNumber());
        return ResponseEntity.ok(response);
    }

}
