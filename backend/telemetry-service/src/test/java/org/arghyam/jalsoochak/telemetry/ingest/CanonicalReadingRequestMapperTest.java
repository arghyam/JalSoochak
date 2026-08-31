package org.arghyam.jalsoochak.telemetry.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CanonicalReadingRequestMapperTest {

    private final ObjectMapper objectMapper =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final CanonicalReadingRequestMapper mapper = new CanonicalReadingRequestMapper(objectMapper);

    @Test
    void mapsCanonicalFieldsIncludingSnakeCaseAndAliases() throws Exception {
        String json = """
                {
                  "reading_url": "https://example.com/meter.jpg",
                  "confirmed_reading": 123.4,
                  "state_scheme_id": "30178236",
                  "center_scheme_id": "30244993",
                  "phone_number": "91XXXXXXXXXX",
                  "reading_date_time": "2026-04-23T07:38:22.031Z"
                }
                """;

        AssamReadingRequest request = mapper.map(objectMapper.readTree(json));

        assertNotNull(request);
        assertEquals("https://example.com/meter.jpg", request.getReadingUrl());
        assertEquals(new BigDecimal("123.4"), request.getConfirmedReading());
        assertEquals("30178236", request.getStateSchemeId());
        // center_scheme_id is a declared @JsonAlias of centreSchemeId
        assertEquals("30244993", request.getCentreSchemeId());
        assertEquals("91XXXXXXXXXX", request.getPhoneNumber());
        assertNotNull(request.getReadingDateTime());
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        String json = """
                {
                  "phone_number": "91XXXXXXXXXX",
                  "state_scheme_id": "30178236",
                  "confirmed_reading": 10,
                  "some_future_field_from_state": "whatever"
                }
                """;

        AssamReadingRequest request = mapper.map(objectMapper.readTree(json));

        assertEquals("91XXXXXXXXXX", request.getPhoneNumber());
        assertEquals("30178236", request.getStateSchemeId());
    }

    @Test
    void nullNodeYieldsEmptyRequestRatherThanNull() {
        AssamReadingRequest request = mapper.map(objectMapper.nullNode());
        assertNotNull(request);
        assertNull(request.getPhoneNumber());
    }

    @Test
    void formatIsCanonical() {
        assertEquals("canonical", mapper.format());
    }
}
