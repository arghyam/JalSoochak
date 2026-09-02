package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shape of {@code POST /api/v1/telemetry/location} against a stored-XSS report filed
 * against it: the claim was that script markup in the request body is persisted and later rendered.
 *
 * <p>It is not, and these tests pin the two reasons why. The coordinates are numeric, so markup
 * cannot bind to them at all; and the contact's WhatsApp profile name is not a bound field, so
 * whatever Glific — or a caller impersonating it — sends under {@code name} is dropped during
 * deserialization rather than carried into the service layer. Re-adding that field would make this
 * suite fail, which is the point: nothing downstream reads it, so binding it only creates a sink.</p>
 */
class LocationReadingRequestJsonTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Validator VALIDATOR;

    static {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    private static final String SCRIPT = "<script>alert(document.cookie)</script>";

    @Test
    void contactNameIsNotBoundAndCannotReachTheServiceLayer() throws Exception {
        String body = """
                {
                  "organization_id": 1,
                  "lat": 12.9716,
                  "lng": 77.5946,
                  "contact": {
                    "phone": "919999900001",
                    "name": "%s",
                    "id": 42
                  }
                }
                """.formatted(SCRIPT);

        LocationReadingRequest request = OBJECT_MAPPER.readValue(body, LocationReadingRequest.class);

        // The request is still accepted — Glific keeps sending the key, and rejecting it would
        // break live location capture for profile names that are perfectly legitimate.
        assertEquals("919999900001", request.resolveContactId());
        assertEquals(0, new BigDecimal("12.9716").compareTo(request.getLatitude()));
        assertEquals(0, new BigDecimal("77.5946").compareTo(request.getLongitude()));
        assertEquals(42L, request.getContact().getId());

        // ...but the payload survives nowhere on the bound object.
        assertFalse(OBJECT_MAPPER.writeValueAsString(request).contains("script"));
    }

    @Test
    void contactDeclaresNoNameField() {
        boolean hasName = Arrays.stream(LocationReadingRequest.Contact.class.getDeclaredFields())
                .anyMatch(field -> "name".equals(field.getName()));

        assertFalse(hasName, "Contact.name is unused; binding it would reintroduce an unread sink");
    }

    @Test
    void scriptMarkupCannotBindToCoordinates() {
        String body = """
                {"lat": "%s", "lng": 77.5946, "contact": {"phone": "919999900001"}}
                """.formatted(SCRIPT);

        assertThrows(InvalidFormatException.class,
                () -> OBJECT_MAPPER.readValue(body, LocationReadingRequest.class));
    }

    @Test
    void unknownTopLevelKeysAreDroppedRatherThanStored() {
        String body = """
                {"location": "%s", "lat": 12.9716, "lng": 77.5946, "contact": {"phone": "919999900001"}}
                """.formatted(SCRIPT);

        LocationReadingRequest request = assertDoesNotThrow(
                () -> OBJECT_MAPPER.readValue(body, LocationReadingRequest.class));

        assertEquals(0, VALIDATOR.validate(request).size());
        assertEquals("919999900001", request.resolveContactId());
    }

    @Test
    void aBodyCarryingOnlyAnUnknownLocationKeyFailsValidation() throws Exception {
        String body = """
                {"location": "%s"}
                """.formatted(SCRIPT);

        LocationReadingRequest request = OBJECT_MAPPER.readValue(body, LocationReadingRequest.class);

        Set<ConstraintViolation<LocationReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(3, violations.size());
        assertTrue(violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList()
                .containsAll(Set.of("latitude", "longitude", "contact")));
    }
}
