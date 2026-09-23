package org.arghyam.jalsoochak.telemetry.controller.webhook;

import org.arghyam.jalsoochak.telemetry.dto.requests.ClosingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.TriggerWelcomeMessageRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.ClosingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.service.ConversationMessageService;
import org.arghyam.jalsoochak.telemetry.service.WelcomeMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoint behaviour of the conversation webhooks. Intro and closing fall back to a {@code 500} the
 * chatbot flow can render; the welcome message answers a {@code 400} instead.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationWebhookController — endpoints")
class ConversationWebhookControllerTest {

    private static final String CONTACT = "919999900001";
    private static final RuntimeException BOOM = new IllegalStateException("downstream failure");

    @Mock
    private ConversationMessageService messageService;
    @Mock
    private WelcomeMessageService welcomeMessageService;

    private ConversationWebhookController controller;

    private final IntroResponse okIntro = IntroResponse.builder().success(true).message("ok").build();

    @BeforeEach
    void setUp() {
        controller = new ConversationWebhookController(messageService, welcomeMessageService);
    }

    private static IntroRequest introRequest() {
        IntroRequest request = new IntroRequest();
        request.setContactId(CONTACT);
        return request;
    }

    @Nested
    @DisplayName("message endpoints")
    class MessageEndpoints {

        @Test
        void introReturnsTheServiceResponse() {
            when(messageService.introMessage(any())).thenReturn(okIntro);

            assertThat(controller.sendIntro(introRequest()).getBody()).isSameAs(okIntro);
        }

        @Test
        void introFallsBackOnFailure() {
            when(messageService.introMessage(any())).thenThrow(BOOM);

            var response = controller.sendIntro(introRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(response.getBody().getMessage()).isEqualTo("Something went wrong. Please try again.");
        }

        @Test
        void closingReturnsTheServiceResponse() {
            ClosingResponse ok = ClosingResponse.builder().success(true).build();
            when(messageService.closingMessage(any())).thenReturn(ok);

            ClosingRequest request = new ClosingRequest();
            request.setContactId(CONTACT);
            assertThat(controller.closingMessage(request).getBody()).isSameAs(ok);
        }

        @Test
        void closingFallsBackOnFailure() {
            when(messageService.closingMessage(any())).thenThrow(BOOM);

            ClosingRequest request = new ClosingRequest();
            request.setContactId(CONTACT);
            var response = controller.closingMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("POST /trigger-welcome-message")
    class TriggerWelcome {

        @Test
        void sendsTheWelcomeMessageForTheResolvedPhone() {
            TriggerWelcomeMessageRequest request = new TriggerWelcomeMessageRequest();
            request.setPhoneNumber(CONTACT);
            when(welcomeMessageService.triggerWelcomeMessage(anyString(), anyBoolean())).thenReturn(okIntro);

            assertThat(controller.triggerWelcomeMessage(request).getBody()).isSameAs(okIntro);
            verify(welcomeMessageService).triggerWelcomeMessage(eq(CONTACT), eq(false));
        }

        @Test
        void passesTheSingleTenantFlagThrough() {
            TriggerWelcomeMessageRequest request = new TriggerWelcomeMessageRequest();
            request.setPhoneNumber(CONTACT);
            request.setIsSingleTenant(true);
            when(welcomeMessageService.triggerWelcomeMessage(anyString(), anyBoolean())).thenReturn(okIntro);

            controller.triggerWelcomeMessage(request);

            verify(welcomeMessageService).triggerWelcomeMessage(eq(CONTACT), eq(true));
        }

        @Test
        void returnsBadRequestWhenTheWelcomeMessageCannotBePrepared() {
            TriggerWelcomeMessageRequest request = new TriggerWelcomeMessageRequest();
            request.setPhoneNumber(CONTACT);
            when(welcomeMessageService.triggerWelcomeMessage(anyString(), anyBoolean())).thenThrow(BOOM);

            var response = controller.triggerWelcomeMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(response.getBody().getCorrelationId()).isEqualTo(CONTACT);
            assertThat(response.getBody().getMessage()).isEqualTo("Welcome message could not be prepared.");
        }

        @Test
        void returnsBadRequestForAMissingRequestBody() {
            when(welcomeMessageService.triggerWelcomeMessage(anyString(), anyBoolean()))
                    .thenThrow(new IllegalArgumentException("phoneNumber/contactId is required"));

            ResponseEntity<IntroResponse> response = controller.triggerWelcomeMessage(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getCorrelationId()).isNull();
        }
    }
}
