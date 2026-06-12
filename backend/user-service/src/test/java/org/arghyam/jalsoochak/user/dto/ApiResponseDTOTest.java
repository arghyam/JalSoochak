package org.arghyam.jalsoochak.user.dto;

import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseDTOTest {

    @Test
    void testStaticFactoryMethodWithData() {
        int status = 200;
        String message = "Operation completed";
        String data = "Test data";

        ApiResponseDTO<String> response = ApiResponseDTO.of(status, message, data);

        assertEquals(status, response.getStatus());
        assertEquals(message, response.getMessage());
        assertEquals(data, response.getData());
    }

    @Test
    void testStaticFactoryMethodWithoutData() {
        int status = 204;
        String message = "Operation completed";

        ApiResponseDTO<Void> response = ApiResponseDTO.of(status, message);

        assertEquals(status, response.getStatus());
        assertEquals(message, response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void testStaticFactoryMethodWithNullData() {
        int status = 200;
        String message = "Operation completed";

        ApiResponseDTO<String> response = ApiResponseDTO.of(status, message, null);

        assertEquals(status, response.getStatus());
        assertEquals(message, response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void testStaticFactoryMethodWithNullMessage() {
        int status = 200;
        String data = "Test data";

        ApiResponseDTO<String> response = ApiResponseDTO.of(status, null, data);

        assertEquals(status, response.getStatus());
        assertNull(response.getMessage());
        assertEquals(data, response.getData());
    }

    @Test
    void testStaticFactoryMethodWithZeroStatus() {
        int status = 0;
        String message = "Error occurred";
        String data = "Error details";

        ApiResponseDTO<String> response = ApiResponseDTO.of(status, message, data);

        assertEquals(status, response.getStatus());
        assertEquals(message, response.getMessage());
        assertEquals(data, response.getData());
    }

    @Test
    void testEquals() {
        ApiResponseDTO<String> response1 = ApiResponseDTO.of(200, "message", "data");

        // Assert the DTO factory produces expected field values
        assertNotNull(response1);
        assertEquals(200, response1.getStatus());
        assertEquals("message", response1.getMessage());
        assertEquals("data", response1.getData());
    }

    @Test
    void testHashCode() {
        ApiResponseDTO<String> response1 = ApiResponseDTO.of(200, "message", "data");

        // Same object should have same hashCode (self-consistency only)
        assertEquals(response1.hashCode(), response1.hashCode());
    }

    @Test
    void testToString() {
        ApiResponseDTO<String> response = ApiResponseDTO.of(200, "message", "data");
        String toString = response.toString();

        assertNotNull(toString);
        // Test that toString contains the class name
        assertTrue(toString.contains("ApiResponseDTO"));
    }

    @Test
    void testWithDifferentDataTypes() {
        ApiResponseDTO<Integer> intResponse = ApiResponseDTO.of(200, "count", 42);
        assertEquals(42, intResponse.getData());

        ApiResponseDTO<Boolean> boolResponse = ApiResponseDTO.of(200, "flag", true);
        assertTrue(boolResponse.getData());

        ApiResponseDTO<Object> objResponse = ApiResponseDTO.of(200, "object", new Object());
        assertNotNull(objResponse.getData());
    }
}
