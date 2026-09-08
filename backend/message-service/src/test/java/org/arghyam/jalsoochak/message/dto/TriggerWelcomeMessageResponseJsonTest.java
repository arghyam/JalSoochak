package org.arghyam.jalsoochak.message.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The welcome trigger's job is to start a Glific flow; naming the person it started it for is not
 * part of that job. Authentication is the real fix for the disclosure, but the payload should not
 * carry PII it has no use for either — a response body outlives the request in proxy logs, browser
 * history and whatever the caller writes it to.
 *
 * <p>The identity that matters operationally is the Glific contact id, which is the handle for
 * chasing a delivery. That stays.
 */
class TriggerWelcomeMessageResponseJsonTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void doesNotCarryOperatorPii() throws Exception {
        TriggerWelcomeMessageResponse response = TriggerWelcomeMessageResponse.builder()
                .success(true)
                .tenantCode("as")
                .contactId(4242L)
                .message("Welcome flow triggered")
                .build();

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(response));
        assertFalse(json.has("name"));
        assertFalse(json.has("phoneNumber"));
        assertFalse(json.has("state"));
    }

    @Test
    void stillCarriesTheOperationalFields() throws Exception {
        TriggerWelcomeMessageResponse response = TriggerWelcomeMessageResponse.builder()
                .success(true)
                .tenantCode("as")
                .contactId(4242L)
                .message("Welcome flow triggered")
                .build();

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(response));
        assertTrue(json.get("success").asBoolean());
        assertEquals("as", json.get("tenantCode").asText());
        assertEquals(4242L, json.get("contactId").asLong());
        assertEquals("Welcome flow triggered", json.get("message").asText());
    }
}
