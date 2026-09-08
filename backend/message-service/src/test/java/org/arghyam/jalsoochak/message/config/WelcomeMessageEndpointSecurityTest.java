package org.arghyam.jalsoochak.message.config;

import org.arghyam.jalsoochak.message.controller.ApiController;
import org.arghyam.jalsoochak.message.kafka.KafkaProducer;
import org.arghyam.jalsoochak.message.service.BusinessService;
import org.arghyam.jalsoochak.message.service.NotificationService;
import org.arghyam.jalsoochak.message.service.WelcomeMessageTriggerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /trigger-welcome-message} used to be {@code permitAll}, which made it an anonymous
 * oracle: {@code WelcomeMessageTriggerService.resolveTenantCodeByPhone} probes every tenant schema
 * until the phone matches, so one unauthenticated request turned a phone number into that person's
 * name, state and tenant. The same call also opts an arbitrary number in to Glific and sends it a
 * WhatsApp message, so it was a spam primitive as well as a disclosure.
 *
 * <p>The api-gateway already refused it — {@code /message/**} falls through to
 * {@code anyExchange().authenticated()} — so the exposure only existed for callers reaching
 * message-service on a non-gateway ingress. That is precisely the gap the gateway/service
 * disagreement creates, and the service is the layer that has to close it.
 */
@WebMvcTest(controllers = ApiController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = "keycloak.resource=jalsoochak-client")
class WelcomeMessageEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private BusinessService businessService;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private KafkaProducer kafkaProducer;

    @MockBean
    private WelcomeMessageTriggerService welcomeMessageTriggerService;

    @Test
    void triggerWelcomeMessageRejectsAnonymousCallers() throws Exception {
        mockMvc.perform(post("/api/v1/message/trigger-welcome-message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"918750293095\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The rejection must happen in the filter chain, before the handler runs. If it reached the
     * service the tenant-schema probe and the Glific opt-in would already have fired, and a 401
     * afterwards would not undo either.
     */
    @Test
    void anonymousCallNeverReachesTheWelcomeTriggerService() throws Exception {
        mockMvc.perform(post("/api/v1/message/trigger-welcome-message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"918750293095\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(welcomeMessageTriggerService);
    }

    /** The rest of the controller was already authenticated; this pins that it stayed that way. */
    @Test
    void otherMessageEndpointsRemainAuthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/message/notifications"))
                .andExpect(status().isUnauthorized());
    }
}
